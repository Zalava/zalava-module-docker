package org.zalava.modules.docker;

import static java.util.Objects.requireNonNull;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Device;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.zalava.api.extensions.managed.ManagedServiceEngine;

/**
 * First concrete engine implementation: Docker through the maintained docker-java Apache HttpClient
 * 5 transport. SEA owns the engine endpoint; the module never exposes the endpoint, socket
 * authority, or any imperative engine handle beyond this contract.
 */
final class DockerManagedServiceEngine implements ManagedServiceEngine {

  /** Deterministic namespace separating SEA-owned resources from everything else on the host. */
  static final String OWNER_LABEL_PREFIX = "org.zalava.";

  static final String OWNER_LABEL = OWNER_LABEL_PREFIX + "managed.owner-module-id";
  static final String SERVICE_LABEL = OWNER_LABEL_PREFIX + "managed.service-id";
  static final String DATA_IDENTITY_LABEL = OWNER_LABEL_PREFIX + "managed.data-identity";

  static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration PULL_TIMEOUT = Duration.ofMinutes(10);
  private static final int STOP_TIMEOUT_SECONDS = 10;
  private static final int MAX_LOG_LINES = 500;
  private static final int MAX_LOG_LINE_LENGTH = 2000;

  private final DockerClient docker;
  private final Duration commandTimeout;

  /** The endpoint must be explicit SEA configuration; it is never derived from module input. */
  DockerManagedServiceEngine(String engineEndpoint, Duration commandTimeout) {
    if (engineEndpoint == null || engineEndpoint.isBlank()) {
      throw new IllegalArgumentException("A Docker engine endpoint must be configured");
    }
    DockerClientConfig config =
        DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(engineEndpoint)
            .build();
    DockerHttpClient httpClient =
        new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .connectionTimeout(commandTimeout(commandTimeout))
            .responseTimeout(commandTimeout(commandTimeout))
            .build();
    this.docker = DockerClientImpl.getInstance(config, httpClient);
    this.commandTimeout = commandTimeout(commandTimeout);
  }

  /** Test seam: reuses a caller-built client so engine logic runs without a local engine. */
  DockerManagedServiceEngine(DockerClient docker, Duration commandTimeout) {
    this.docker = requireNonNull(docker, "docker");
    this.commandTimeout = commandTimeout(commandTimeout);
  }

  private static Duration commandTimeout(Duration commandTimeout) {
    requireNonNull(commandTimeout, "commandTimeout");
    if (commandTimeout.isNegative() || commandTimeout.isZero()) {
      throw new IllegalArgumentException("commandTimeout must be positive");
    }
    return commandTimeout;
  }

  /**
   * Negotiates the Engine API against the configured endpoint. Called once before the first
   * reconciliation so a missing, denied, or incompatible engine fails at startup instead of during
   * lifecycle work.
   */
  void verifyEngineCompatibility() {
    docker.pingCmd().exec();
  }

  @Override
  public Observation inspect(String serviceId) {
    InspectContainerResponse inspect = findOwnedContainer(serviceId);
    if (inspect == null) {
      return new Observation(false, false, false, null, null);
    }
    boolean running = Boolean.TRUE.equals(inspect.getState().getRunning());
    HealthState health = inspect.getState().getHealth();
    // A container without a HEALTHCHECK definition has no health state; running then counts as
    // ready, while an unhealthy or starting healthcheck keeps the service not ready.
    boolean ready = running && (health == null || "healthy".equals(health.getStatus()));
    return new Observation(true, running, ready, ownerOf(inspect), dataIdentityOf(inspect));
  }

  @Override
  public void create(Request request) {
    String serviceId = request.serviceId();
    InspectContainerResponse occupant = probeName(serviceId);
    if (occupant != null) {
      Map<String, String> labels =
          occupant.getConfig() == null ? null : occupant.getConfig().getLabels();
      if (labels != null && serviceId.equals(labels.get(SERVICE_LABEL))) {
        throw new IllegalStateException("SEA-owned container already exists: " + serviceId);
      }
      throw new IllegalStateException(
          "A foreign container occupies the deterministic SEA name: " + serviceId);
    }
    String imageReference = request.desiredState().artifactReference();
    // Digest-verified acquisition: the pull resolves the digest reference itself, and the concrete
    // image id is what the container is created from, so the running image is exactly the pinned
    // content of this record's desired revision.
    boolean pulled;
    try {
      pulled =
          docker
              .pullImageCmd(imageReference)
              .exec(new PullImageResultCallback())
              .awaitCompletion(PULL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted pulling the image for " + serviceId, ex);
    }
    if (!pulled) {
      throw new IllegalStateException("Timed out pulling the digest-pinned image for " + serviceId);
    }
    String imageId = docker.inspectImageCmd(imageReference).exec().getId();
    if (imageId == null || imageId.isBlank()) {
      throw new IllegalStateException("Engine returned no image id for " + imageReference);
    }
    CreateContainerResponse container =
        docker
            .createContainerCmd(imageReference)
            .withName(serviceId)
            .withImage(imageId)
            .withLabels(
                Map.of(
                    OWNER_LABEL,
                    request.grant().moduleId(),
                    SERVICE_LABEL,
                    serviceId,
                    DATA_IDENTITY_LABEL,
                    request.ownedDataIdentity()))
            .withHostConfig(hostConfig(request))
            .withExposedPorts(
                request.desiredState().ports().stream()
                    .map(ExposedPort::tcp)
                    .toArray(ExposedPort[]::new))
            .exec();
    if (container == null || container.getId() == null || container.getId().isBlank()) {
      throw new IllegalStateException("Engine returned no container id for " + serviceId);
    }
  }

  @Override
  public void start(String serviceId) {
    docker.startContainerCmd(requireOwnedContainerName(serviceId)).exec();
  }

  @Override
  public void stop(String serviceId) {
    docker
        .stopContainerCmd(requireOwnedContainerName(serviceId))
        .withTimeout(STOP_TIMEOUT_SECONDS)
        .exec();
  }

  @Override
  public void remove(String serviceId) {
    // Contract v2: removing the owned container under the deterministic name is what lets a later
    // create replace it for a digest promotion or rollback. Foreign occupants never resolve here,
    // so the engine cannot delete host resources outside its own ownership namespace.
    docker.removeContainerCmd(requireOwnedContainerName(serviceId)).withForce(true).exec();
  }

  @Override
  public List<String> recentLogs(String serviceId, int maximumLines) {
    int bounded = Math.min(Math.max(0, maximumLines), MAX_LOG_LINES);
    if (bounded == 0 || findOwnedContainer(serviceId) == null) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    ResultCallback.Adapter<Frame> callback =
        new ResultCallback.Adapter<>() {
          @Override
          public void onNext(Frame frame) {
            if (frame == null || frame.getPayload() == null || lines.size() >= bounded) {
              return;
            }
            String line = new String(frame.getPayload(), StandardCharsets.UTF_8).strip();
            lines.add(
                line.length() > MAX_LOG_LINE_LENGTH
                    ? line.substring(0, MAX_LOG_LINE_LENGTH)
                    : line);
          }
        };
    try {
      docker
          .logContainerCmd(requireOwnedContainerName(serviceId))
          .withStdOut(true)
          .withStdErr(true)
          .withTail(bounded)
          .exec(callback);
      callback.awaitCompletion(commandTimeout.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      closeQuietly(callback);
    } catch (RuntimeException ex) {
      closeQuietly(callback); // Diagnostic only; log failures must never affect reconciliation.
    }
    return List.copyOf(lines);
  }

  private static void closeQuietly(ResultCallback<?> callback) {
    try {
      callback.close();
    } catch (java.io.IOException ignored) {
      // Diagnostic-only path; a failed stream close never affects reconciliation.
    }
  }

  /**
   * Binds only resources present in BOTH the desired state and the approved grant, so undeclared
   * mounts, devices, or widened grants can never reach the engine. Ports publish on loopback of the
   * granted host port; host networking and socket grants stay out of reach by construction.
   */
  static HostConfig hostConfig(Request request) {
    var desired = request.desiredState();
    var grant = request.grant();
    Set<String> dataPaths = new LinkedHashSet<>(desired.dataPaths());
    dataPaths.retainAll(grant.dataPaths());
    Set<Integer> ports = new LinkedHashSet<>(desired.ports());
    ports.retainAll(grant.ports());
    Set<String> devices = new LinkedHashSet<>(desired.devices());
    devices.retainAll(grant.devices());
    if (dataPaths.size() != desired.dataPaths().size()
        || ports.size() != desired.ports().size()
        || devices.size() != desired.devices().size()) {
      throw new IllegalStateException(
          "Desired state requests resources outside the approved grant: " + request.serviceId());
    }

    List<Bind> binds = new ArrayList<>();
    for (String path : dataPaths) {
      requireCanonicalPath(path, "dataPaths");
      binds.add(new Bind(path, new Volume(path)));
    }
    Ports bindings = new Ports();
    for (int port : ports) {
      bindings.bind(ExposedPort.tcp(port), Ports.Binding.bindIpAndPort("127.0.0.1", port));
    }
    Device[] grantedDevices =
        devices.stream()
            .map(
                path -> {
                  requireCanonicalDevice(path);
                  return new Device("rwm", path, path);
                })
            .toArray(Device[]::new);

    return HostConfig.newHostConfig()
        .withBinds(binds)
        .withPortBindings(bindings)
        .withDevices(grantedDevices)
        .withNanoCPUs(desired.limits().cpuMillis() * 1_000_000L)
        .withMemory(desired.limits().memoryBytes())
        .withPidsLimit((long) desired.limits().processLimit());
  }

  /** Defensive re-check mirroring the SEA-owned path rules that validated the grant. */
  private static void requireCanonicalPath(String value, String name) {
    if (value == null
        || !value.equals(java.nio.file.Path.of(value).normalize().toString())
        || "/".equals(value)) {
      throw new IllegalStateException(name + " must contain canonical non-root absolute paths");
    }
  }

  private static void requireCanonicalDevice(String value) {
    requireCanonicalPath(value, "devices");
    if (!value.startsWith("/dev/")) {
      throw new IllegalStateException("devices must be under /dev");
    }
  }

  /** Any container holding the name, regardless of ownership; null when the name is free. */
  private InspectContainerResponse probeName(String serviceId) {
    if (serviceId == null || serviceId.isBlank()) {
      throw new IllegalArgumentException("serviceId must not be blank");
    }
    try {
      return docker.inspectContainerCmd(serviceId).exec();
    } catch (NotFoundException ex) {
      return null;
    }
  }

  private InspectContainerResponse findOwnedContainer(String serviceId) {
    InspectContainerResponse inspect = probeName(serviceId);
    if (inspect == null) {
      return null;
    }
    Map<String, String> labels =
        inspect.getConfig() == null ? null : inspect.getConfig().getLabels();
    if (labels == null || !serviceId.equals(labels.get(SERVICE_LABEL))) {
      // The name exists but was not created by this SEA-owned namespace: report absence so the
      // reconciler never operates on, restarts, or attaches to a foreign container.
      return null;
    }
    return inspect;
  }

  private String requireOwnedContainerName(String serviceId) {
    if (findOwnedContainer(serviceId) == null) {
      throw new IllegalArgumentException("No SEA-owned container: " + serviceId);
    }
    return serviceId;
  }

  private String ownerOf(InspectContainerResponse inspect) {
    return inspect.getConfig().getLabels().get(OWNER_LABEL);
  }

  private String dataIdentityOf(InspectContainerResponse inspect) {
    return inspect.getConfig().getLabels().get(DATA_IDENTITY_LABEL);
  }
}

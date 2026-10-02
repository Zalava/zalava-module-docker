package org.zalava.modules.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.command.PullImageResultCallback;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.zalava.InvocationContext;
import org.zalava.ProviderCapabilities;
import org.zalava.ProviderDescriptor;
import org.zalava.ZalavaOperationResult;
import org.zalava.ZalavaProvider;
import org.zalava.ZalavaToolDescriptor;
import org.zalava.ZalavaToolInputSchemas;
import tools.jackson.databind.JsonNode;

/** Explicit user operations; never changes containers controlled by SEA's reconciler. */
final class DockerContainerProvider implements ZalavaProvider {
  static final String OWNER_LABEL = "org.zalava.user.provider-id";
  private static final Set<String> MUTATIONS =
      Set.of(
          "runContainer", "startContainer", "stopContainer", "restartContainer", "removeContainer");
  private final DockerClient docker;
  private final boolean writable;
  private final boolean manageExternal;
  private final boolean allowPublicPorts;

  DockerContainerProvider(
      DockerClient docker, boolean writable, boolean manageExternal, boolean allowPublicPorts) {
    this.docker = docker;
    this.writable = writable;
    this.manageExternal = manageExternal;
    this.allowPublicPorts = allowPublicPorts;
  }

  @Override
  public ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        DockerProviderFactory.ID,
        DockerSeaModule.MODULE_ID,
        DockerProviderFactory.ID,
        "Docker Containers",
        "List Docker containers, inspect published ports, and manage user-requested services.",
        DockerSeaModule.version(),
        capabilities(),
        List.of("docker"),
        Map.of(
            "writable",
            Boolean.toString(writable),
            "manageExternalContainers",
            Boolean.toString(manageExternal)));
  }

  @Override
  public ProviderCapabilities capabilities() {
    return new ProviderCapabilities(true, false, false, false, false, false, false, false);
  }

  @Override
  public List<ZalavaToolDescriptor> listTools() {
    List<ZalavaToolDescriptor> tools = new ArrayList<>();
    tools.add(
        tool(
            "listContainers",
            "List Docker containers and published ports (running by default).",
            Map.of("all", Map.of("type", "boolean"))));
    tools.add(
        tool(
            "inspectContainer",
            "Inspect container state, ownership and port bindings; excludes secrets.",
            Map.of("container", string()),
            "container"));
    tools.add(
        tool(
            "containerLogs",
            "Read up to 200 recent container log frames, bounded and diagnostic only.",
            Map.of("container", string(), "lines", integer(1, 200)),
            "container"));
    if (writable) {
      tools.add(
          tool(
              "runContainer",
              "Pull an image, create and start a named container. Publish ports at creation; defaults to localhost. Does not replace existing containers.",
              Map.of(
                  "name",
                  string(),
                  "image",
                  string(),
                  "ports",
                  Map.of(
                      "type",
                      "array",
                      "maxItems",
                      32,
                      "items",
                      ZalavaToolInputSchemas.object(
                          Map.of(
                              "containerPort",
                              integer(1, 65535),
                              "hostPort",
                              integer(1, 65535),
                              "hostIp",
                              Map.of("type", "string", "enum", List.of("127.0.0.1", "0.0.0.0")),
                              "protocol",
                              Map.of("type", "string", "enum", List.of("tcp", "udp"))),
                          "containerPort",
                          "hostPort"))),
              "name",
              "image"));
      for (String name :
          List.of("startContainer", "stopContainer", "restartContainer", "removeContainer")) {
        tools.add(
            tool(
                name,
                name.equals("removeContainer")
                    ? "Remove a stopped container without deleting volumes. Requires confirmation."
                    : name + ": operate on an eligible container. Requires confirmation.",
                Map.of("container", string()),
                "container"));
      }
    }
    return List.copyOf(tools);
  }

  private static ZalavaToolDescriptor tool(
      String name, String description, Map<String, Object> properties, String... required) {
    return new ZalavaToolDescriptor(
        name,
        description,
        MUTATIONS.contains(name),
        List.of("docker"),
        ZalavaToolInputSchemas.object(properties, required));
  }

  private static Map<String, Object> string() {
    return Map.of("type", "string", "minLength", 1);
  }

  private static Map<String, Object> integer(int min, int max) {
    return Map.of("type", "integer", "minimum", min, "maximum", max);
  }

  @Override
  public ZalavaOperationResult callTool(
      String name, JsonNode arguments, InvocationContext context) {
    if (MUTATIONS.contains(name)) {
      if (!writable) throw new UnsupportedOperationException("Docker provider is read-only");
      if (context == null || !context.confirmed()) {
        throw new IllegalArgumentException("Docker mutation requires confirmation");
      }
    }
    return ZalavaOperationResult.success(
        switch (name) {
          case "listContainers" -> list(arguments);
          case "inspectContainer" -> inspect(arguments);
          case "containerLogs" -> logs(arguments);
          case "runContainer" -> run(arguments);
          case "startContainer", "stopContainer", "restartContainer", "removeContainer" ->
              mutate(name, arguments);
          default -> throw new UnsupportedOperationException("Unknown Docker tool: " + name);
        });
  }

  private Object list(JsonNode arguments) {
    fields(arguments, Set.of("all"));
    boolean all = false;
    if (arguments.has("all")) {
      if (!arguments.get("all").isBoolean())
        throw new IllegalArgumentException("all must be boolean");
      all = arguments.get("all").asBoolean();
    }
    var containers = docker.listContainersCmd().withShowAll(all).exec();
    var items =
        containers.stream()
            .limit(100)
            .map(
                container -> {
                  Map<String, Object> item = new LinkedHashMap<>();
                  item.put("id", container.getId());
                  item.put(
                      "names",
                      container.getNames() == null
                          ? List.of()
                          : Arrays.asList(container.getNames()));
                  item.put("image", container.getImage());
                  item.put("state", container.getState());
                  item.put("status", container.getStatus());
                  item.put("ownership", ownership(container.getLabels()));
                  item.put(
                      "ports",
                      container.getPorts() == null
                          ? List.of()
                          : Arrays.stream(container.getPorts())
                              .map(
                                  port -> {
                                    Map<String, Object> binding = new LinkedHashMap<>();
                                    binding.put("containerPort", port.getPrivatePort());
                                    binding.put("hostPort", port.getPublicPort());
                                    binding.put("hostIp", port.getIp());
                                    binding.put("protocol", port.getType());
                                    return binding;
                                  })
                              .toList());
                  return item;
                })
            .toList();
    return Map.of("containers", items, "truncated", containers.size() > 100);
  }

  private Object inspect(JsonNode arguments) {
    fields(arguments, Set.of("container"));
    return summary(docker.inspectContainerCmd(text(arguments, "container", 256)).exec());
  }

  private Map<String, Object> summary(InspectContainerResponse container) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", container.getId());
    result.put("name", container.getName());
    result.put("image", container.getConfig().getImage());
    result.put("running", container.getState().getRunning());
    result.put("status", container.getState().getStatus());
    result.put("ownership", ownership(container.getConfig().getLabels()));
    List<Map<String, Object>> ports = new ArrayList<>();
    var bindings = container.getHostConfig().getPortBindings();
    if (bindings != null)
      bindings
          .getBindings()
          .forEach(
              (port, published) -> {
                if (published != null)
                  for (var binding : published) {
                    ports.add(
                        Map.of(
                            "containerPort",
                            port.getPort(),
                            "protocol",
                            port.getProtocol().toString(),
                            "hostIp",
                            binding.getHostIp() == null ? "" : binding.getHostIp(),
                            "hostPort",
                            binding.getHostPortSpec() == null ? "" : binding.getHostPortSpec()));
                  }
              });
    result.put("ports", ports);
    return result;
  }

  private Object run(JsonNode arguments) {
    fields(arguments, Set.of("name", "image", "ports"));
    String name = text(arguments, "name", 128);
    if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*") || name.startsWith("sea-")) {
      throw new IllegalArgumentException(
          "name must be a Docker name outside the reserved sea- namespace");
    }
    String image = text(arguments, "image", 512);
    if (image.chars().anyMatch(Character::isWhitespace))
      throw new IllegalArgumentException("image must not contain whitespace");
    Ports ports = new Ports();
    List<ExposedPort> exposed = new ArrayList<>();
    if (arguments.has("ports")) {
      JsonNode requested = arguments.get("ports");
      if (!requested.isArray() || requested.size() > 32)
        throw new IllegalArgumentException("ports must be an array of at most 32 mappings");
      for (JsonNode mapping : requested) {
        fields(mapping, Set.of("containerPort", "hostPort", "hostIp", "protocol"));
        int target = number(mapping, "containerPort", 1, 65535);
        int host = number(mapping, "hostPort", 1, 65535);
        String ip = mapping.has("hostIp") ? text(mapping, "hostIp", 32) : "127.0.0.1";
        if (!ip.equals("127.0.0.1") && !(allowPublicPorts && ip.equals("0.0.0.0"))) {
          throw new IllegalArgumentException(
              "hostIp must be 127.0.0.1; 0.0.0.0 requires allowPublicPorts configuration");
        }
        String protocol = mapping.has("protocol") ? text(mapping, "protocol", 3) : "tcp";
        ExposedPort port =
            switch (protocol) {
              case "tcp" -> ExposedPort.tcp(target);
              case "udp" -> ExposedPort.udp(target);
              default -> throw new IllegalArgumentException("protocol must be tcp or udp");
            };
        ports.bind(port, Ports.Binding.bindIpAndPort(ip, host));
        exposed.add(port);
      }
    }
    // Check collisions before acquiring an image. Docker still enforces uniqueness at creation.
    try {
      docker.inspectContainerCmd(name).exec();
      throw new IllegalArgumentException("Container already exists: " + name);
    } catch (com.github.dockerjava.api.exception.NotFoundException absent) {
      // A genuinely absent name can be created. Other engine errors must propagate.
    }
    try (var callback = new PullImageResultCallback()) {
      if (!docker.pullImageCmd(image).exec(callback).awaitCompletion(120, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out pulling image");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted pulling image", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not close image pull", exception);
    }
    String imageId = docker.inspectImageCmd(image).exec().getId();
    if (imageId == null || imageId.isBlank())
      throw new IllegalStateException("Engine returned no image id");
    var created =
        docker
            .createContainerCmd(imageId)
            .withName(name)
            .withLabels(Map.of(OWNER_LABEL, DockerProviderFactory.ID))
            .withExposedPorts(exposed)
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withPortBindings(ports)
                    .withMemory(512L * 1024 * 1024)
                    .withNanoCPUs(1_000_000_000L)
                    .withPidsLimit(256L))
            .exec();
    String id = created.getId();
    if (id == null || id.isBlank())
      throw new IllegalStateException("Engine returned no container id");
    try {
      docker.startContainerCmd(id).exec();
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Container created but start failed; inspect or remove container " + id, exception);
    }
    return Map.of("id", id, "name", name, "started", true);
  }

  private Object mutate(String operation, JsonNode arguments) {
    fields(arguments, Set.of("container"));
    var container = docker.inspectContainerCmd(text(arguments, "container", 256)).exec();
    String owner = ownership(container.getConfig().getLabels());
    if (owner.equals("sea-managed-service")) {
      throw new IllegalArgumentException(
          "Use SEA managed-service lifecycle for reconciler-owned containers");
    }
    if (!owner.equals("sea-user") && !manageExternal) {
      throw new IllegalArgumentException(
          "External container mutations require manageExternalContainers configuration");
    }
    // Resolve names once, then use the immutable id to avoid name-replacement races.
    String id = container.getId();
    if (id == null || id.isBlank())
      throw new IllegalStateException("Engine returned no container id");
    switch (operation) {
      case "startContainer" -> docker.startContainerCmd(id).exec();
      case "stopContainer" -> docker.stopContainerCmd(id).withTimeout(10).exec();
      case "restartContainer" -> docker.restartContainerCmd(id).withTimeout(10).exec();
      case "removeContainer" -> {
        if (Boolean.TRUE.equals(container.getState().getRunning())) {
          throw new IllegalArgumentException("Stop the container before removing it");
        }
        docker.removeContainerCmd(id).withForce(false).withRemoveVolumes(false).exec();
      }
      default -> throw new UnsupportedOperationException(operation);
    }
    return Map.of("id", id, "operation", operation);
  }

  private Object logs(JsonNode arguments) {
    fields(arguments, Set.of("container", "lines"));
    String id = docker.inspectContainerCmd(text(arguments, "container", 256)).exec().getId();
    int limit = arguments.has("lines") ? number(arguments, "lines", 1, 200) : 100;
    List<String> frames = new ArrayList<>();
    try (var callback =
        new ResultCallback.Adapter<Frame>() {
          @Override
          public void onNext(Frame frame) {
            if (frame == null || frame.getPayload() == null) return;
            synchronized (frames) {
              if (frames.size() < limit) {
                byte[] payload = frame.getPayload();
                frames.add(
                    new String(payload, 0, Math.min(payload.length, 2000), StandardCharsets.UTF_8));
              }
            }
          }
        }) {
      boolean complete =
          docker
              .logContainerCmd(id)
              .withStdOut(true)
              .withStdErr(true)
              .withFollowStream(false)
              .withTail(limit)
              .exec(callback)
              .awaitCompletion(30, TimeUnit.SECONDS);
      synchronized (frames) {
        return Map.of("id", id, "frames", List.copyOf(frames), "complete", complete);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted reading logs", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not close log stream", exception);
    }
  }

  private static String ownership(Map<String, String> labels) {
    if (labels == null) return "external";
    if (labels.keySet().stream().anyMatch(key -> key.startsWith("org.zalava.managed."))) {
      return "sea-managed-service";
    }
    return DockerProviderFactory.ID.equals(labels.get(OWNER_LABEL)) ? "sea-user" : "external";
  }

  private static void fields(JsonNode arguments, Set<String> allowed) {
    if (arguments == null || !arguments.isObject())
      throw new IllegalArgumentException("arguments must be an object");
    for (String field : arguments.propertyNames()) {
      if (!allowed.contains(field))
        throw new IllegalArgumentException("Unknown argument: " + field);
    }
  }

  private static String text(JsonNode arguments, String name, int maximum) {
    JsonNode value = arguments.get(name);
    if (value == null
        || !value.isString()
        || value.asString().isBlank()
        || value.asString().length() > maximum
        || value.asString().chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          name + " must be a nonblank string of at most " + maximum + " characters");
    }
    return value.asString();
  }

  private static int number(JsonNode arguments, String name, int minimum, int maximum) {
    JsonNode value = arguments.get(name);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.asInt() < minimum
        || value.asInt() > maximum) {
      throw new IllegalArgumentException(
          name + " must be an integer between " + minimum + " and " + maximum);
    }
    return value.asInt();
  }

  @Override
  public void close() {
    try {
      docker.close();
    } catch (IOException exception) {
      throw new IllegalStateException("Could not close Docker client", exception);
    }
  }
}

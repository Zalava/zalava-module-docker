package org.zalava.modules.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.exception.NotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.zalava.api.extensions.managed.ManagedServiceDesiredState;
import org.zalava.api.extensions.managed.ManagedServiceEngine;
import org.zalava.api.extensions.managed.ManagedServiceLifecycle;
import org.zalava.api.extensions.managed.ManagedServiceLimits;
import org.zalava.api.extensions.managed.ManagedServiceResourceGrant;

/**
 * Contract tests against a real Docker engine. Tagged {@code docker-engine} and excluded from the
 * default {@code test} task; run with {@code ./gradlew realEngineTest} on a host where the
 * configured engine endpoint is reachable. Every resource created here is cleaned up in the test.
 */
@Tag("docker-engine")
class DockerManagedServiceEngineRealEngineIT {
  private static final String SERVICE_ID = "zalava-it-managed-engine";
  private static final String DATA_IDENTITY = "data-it-1";
  // Digest-pinned so the engine test never drifts with mutable tags. The image must be
  // long-running by default: the lifecycle assertions require a container that stays up.
  private static final String PINNED =
      "redis@sha256:12da49daa000c2be4d55118574f889c8cc298140ecf343d04f345c818f227823";

  private static DockerManagedServiceEngine engine() {
    return new DockerManagedServiceEngine("unix:///var/run/docker.sock", Duration.ofSeconds(30));
  }

  private static ManagedServiceEngine.Request request() {
    return new ManagedServiceEngine.Request(
        SERVICE_ID,
        new ManagedServiceDesiredState(
            SERVICE_ID,
            PINNED,
            "1",
            ManagedServiceLifecycle.RUNNING,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            new ManagedServiceLimits(200, 64L * 1024 * 1024, 16),
            Duration.ofSeconds(30),
            3),
        new ManagedServiceResourceGrant(
            "home-module",
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            new ManagedServiceLimits(1_000, 256L * 1024 * 1024, 32),
            Duration.ofSeconds(30),
            3),
        DATA_IDENTITY);
  }

  @Test
  void verifiesEngineCompatibility() {
    engine().verifyEngineCompatibility();
  }

  @Test
  void runsTheFullLifecycleOnTheRealEngine() {
    DockerManagedServiceEngine engine = engine();
    engine.verifyEngineCompatibility();

    try {
      engine.create(request());

      ManagedServiceEngine.Observation created = engine.inspect(SERVICE_ID);
      assertThat(created.exists()).isTrue();
      assertThat(created.ownerModuleId()).isEqualTo("home-module");
      assertThat(created.dataIdentity()).isEqualTo(DATA_IDENTITY);

      engine.start(SERVICE_ID);
      ManagedServiceEngine.Observation running = engine.inspect(SERVICE_ID);
      assertThat(running.running()).isTrue();
      assertThat(running.ready()).isTrue(); // busybox sleep has no healthcheck.

      List<String> logs = engine.recentLogs(SERVICE_ID, 10);
      assertThat(logs).isNotNull();

      engine.stop(SERVICE_ID);
      assertThat(engine.inspect(SERVICE_ID).running()).isFalse();
    } finally {
      cleanup(engine);
    }
  }

  @Test
  void refusesToCreateWhenAForeignContainerHoldsTheName() {
    DockerManagedServiceEngine engine = engine();
    try {
      new ForeignProbe().hold();
      assertThatThrownBy(() -> engine.create(request()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("foreign");
    } finally {
      cleanup(engine);
    }
  }

  @Test
  void refusesToRemoveAForeignContainerHoldingTheName() {
    DockerManagedServiceEngine engine = engine();
    try {
      new ForeignProbe().hold();
      assertThatThrownBy(() -> engine.remove(SERVICE_ID))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("No Zalava-owned container");
      // The foreign occupant must survive the refused removal; Zalava never deletes outside its
      // ownership namespace.
      assertThat(new ForeignProbe().exists()).isTrue();
    } finally {
      cleanup(engine);
    }
  }

  @Test
  void reportsAnUnknownServiceAsAbsentAndRefusesRemoval() {
    DockerManagedServiceEngine engine = engine();

    assertThat(engine.inspect(SERVICE_ID).exists()).isFalse();
    assertThatThrownBy(() -> engine.remove(SERVICE_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Minimal foreign occupant created directly through docker-java, outside the Zalava namespace.
   */
  private static final class ForeignProbe {
    private final com.github.dockerjava.api.DockerClient docker =
        com.github.dockerjava.core.DockerClientImpl.getInstance(
            com.github.dockerjava.core.DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build(),
            new com.github.dockerjava.httpclient5.ApacheDockerHttpClient.Builder()
                .dockerHost(java.net.URI.create("unix:///var/run/docker.sock"))
                .build());

    void hold() {
      try {
        docker
            .pullImageCmd(PINNED)
            .exec(new com.github.dockerjava.core.command.PullImageResultCallback())
            .awaitCompletion();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while pulling the probe image", ex);
      }
      docker
          .createContainerCmd(PINNED)
          .withName(SERVICE_ID)
          .withImage(PINNED)
          .withLabels(Map.of("made-by", "someone-else"))
          .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig())
          .exec();
    }

    void remove() {
      docker.removeContainerCmd(SERVICE_ID).withForce(true).exec();
    }

    boolean exists() {
      try {
        docker.inspectContainerCmd(SERVICE_ID).exec();
        return true;
      } catch (NotFoundException ex) {
        return false;
      }
    }
  }

  private static void cleanup(DockerManagedServiceEngine engine) {
    try {
      engine.stop(SERVICE_ID);
    } catch (RuntimeException ignored) {
      // already stopped or never created
    }
    try {
      // Contract v2: engine-owned removal under the deterministic Zalava name.
      engine.remove(SERVICE_ID);
    } catch (RuntimeException ignored) {
      // never created or not owned
    }
    try {
      // The foreign-name refusal test must still clean up the occupant it created directly.
      new ForeignProbe().remove();
    } catch (RuntimeException ignored) {
      // never created
    }
  }
}

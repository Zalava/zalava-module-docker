package org.zalava.modules.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zalava.api.extensions.managed.ManagedServiceEngine;

/**
 * Engine-free coverage of the contract-v2 ownership boundary: removal must only ever address the
 * SEA-owned resource namespace, so a foreign or unknown occupant can never be deleted through the
 * engine. A mocked {@link DockerClient} records whether the destructive command was issued at all.
 */
class DockerManagedServiceEngineTest {

  private static final String SERVICE_ID = "sea-managed-service";

  private DockerClient docker;
  private DockerManagedServiceEngine engine;

  @BeforeEach
  void setUp() {
    docker = mock(DockerClient.class);
    engine = new DockerManagedServiceEngine(docker, Duration.ofSeconds(5));
  }

  private void nameHeldWithLabels(Map<String, String> labels) {
    InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
    InspectContainerResponse inspect = mock(InspectContainerResponse.class);
    ContainerConfig config = mock(ContainerConfig.class);
    when(docker.inspectContainerCmd(SERVICE_ID)).thenReturn(inspectCmd);
    when(inspectCmd.exec()).thenReturn(inspect);
    when(inspect.getConfig()).thenReturn(config);
    when(config.getLabels()).thenReturn(labels);
  }

  @Test
  void removesAnOwnedContainerUnderTheDeterministicName() {
    nameHeldWithLabels(Map.of(DockerManagedServiceEngine.SERVICE_LABEL, SERVICE_ID));
    RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
    when(docker.removeContainerCmd(SERVICE_ID)).thenReturn(removeCmd);
    when(removeCmd.withForce(true)).thenReturn(removeCmd);

    engine.remove(SERVICE_ID);

    verify(removeCmd).exec();
  }

  @Test
  void refusesToRemoveAForeignContainerHoldingTheName() {
    nameHeldWithLabels(Map.of(DockerManagedServiceEngine.SERVICE_LABEL, "someone-else"));

    assertThatThrownBy(() -> engine.remove(SERVICE_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No SEA-owned container");
    verify(docker, never()).removeContainerCmd(any());
  }

  @Test
  void refusesToRemoveAContainerWithoutTheOwnershipLabel() {
    nameHeldWithLabels(null);

    assertThatThrownBy(() -> engine.remove(SERVICE_ID))
        .isInstanceOf(IllegalArgumentException.class);
    verify(docker, never()).removeContainerCmd(any());
  }

  @Test
  void refusesToRemoveAnUnknownService() {
    InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
    when(docker.inspectContainerCmd(SERVICE_ID)).thenReturn(inspectCmd);
    when(inspectCmd.exec()).thenThrow(new NotFoundException("missing"));

    assertThatThrownBy(() -> engine.remove(SERVICE_ID))
        .isInstanceOf(IllegalArgumentException.class);
    verify(docker, never()).removeContainerCmd(any());
  }

  @Test
  void reportsAnAbsentObservationForAForeignContainer() {
    nameHeldWithLabels(Map.of(DockerManagedServiceEngine.SERVICE_LABEL, "someone-else"));

    ManagedServiceEngine.Observation observation = engine.inspect(SERVICE_ID);

    assertThat(observation.exists()).isFalse();
  }
}

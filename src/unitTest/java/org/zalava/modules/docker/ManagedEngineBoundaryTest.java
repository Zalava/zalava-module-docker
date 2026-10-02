package org.zalava.modules.docker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.dockerjava.api.*;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.PullImageResultCallback;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.zalava.api.extensions.managed.*;

class ManagedEngineBoundaryTest {
  private static final String ID = "sea-owned";

  @Test
  void isolatesForeignResourcesAndObservesReadiness() {
    DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    var engine = new DockerManagedServiceEngine(docker, Duration.ofSeconds(1));
    assertThatThrownBy(() -> new DockerManagedServiceEngine(docker, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DockerManagedServiceEngine(docker, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.inspect(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> engine.inspect(" ")).isInstanceOf(IllegalArgumentException.class);
    when(docker.inspectContainerCmd(ID).exec()).thenThrow(new NotFoundException("absent"));
    assertThat(engine.inspect(ID).exists()).isFalse();
    assertThatThrownBy(() -> engine.start(ID)).isInstanceOf(IllegalArgumentException.class);
    assertThat(engine.recentLogs(ID, 20)).isEmpty();
    assertThat(engine.recentLogs(ID, 0)).isEmpty();
    InspectContainerResponse container = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
    InspectContainerCmd inspect = docker.inspectContainerCmd(ID);
    doReturn(container).when(inspect).exec();
    when(container.getConfig().getLabels()).thenReturn(null);
    assertThat(engine.inspect(ID).exists()).isFalse();
    when(container.getConfig().getLabels()).thenReturn(Map.of());
    assertThat(engine.inspect(ID).exists()).isFalse();
    assertThatThrownBy(() -> engine.create(request(Set.of(), Set.of(), Set.of(), Set.of())))
        .isInstanceOf(IllegalStateException.class);
    when(container.getConfig().getLabels())
        .thenReturn(
            Map.of(
                DockerManagedServiceEngine.SERVICE_LABEL,
                ID,
                DockerManagedServiceEngine.OWNER_LABEL,
                "owner",
                DockerManagedServiceEngine.DATA_IDENTITY_LABEL,
                "data"));
    when(container.getState().getRunning()).thenReturn(true);
    when(container.getState().getHealth()).thenReturn(null);
    assertThat(engine.inspect(ID).ready()).isTrue();
    assertThat(engine.inspect(ID).ownerModuleId()).isEqualTo("owner");
    assertThat(engine.inspect(ID).dataIdentity()).isEqualTo("data");
    HealthState health = mock(HealthState.class);
    when(container.getState().getHealth()).thenReturn(health);
    when(health.getStatus()).thenReturn("starting");
    assertThat(engine.inspect(ID).ready()).isFalse();
    when(health.getStatus()).thenReturn("healthy");
    assertThat(engine.inspect(ID).ready()).isTrue();
    when(container.getState().getRunning()).thenReturn(false);
    assertThat(engine.inspect(ID).ready()).isFalse();
    assertThatThrownBy(() -> engine.create(request(Set.of(), Set.of(), Set.of(), Set.of())))
        .isInstanceOf(IllegalStateException.class);
    engine.start(ID);
    engine.stop(ID);
    engine.remove(ID);
    engine.verifyEngineCompatibility();
  }

  @Test
  void createsOnlyApprovedDigestPinnedResourcesAndRejectsAcquisitionFailures() throws Exception {
    DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    var engine = new DockerManagedServiceEngine(docker, Duration.ofSeconds(1));
    when(docker.inspectContainerCmd(ID).exec()).thenThrow(new NotFoundException("absent"));
    PullImageResultCallback pull = mock(PullImageResultCallback.class);
    PullImageCmd pullCommand = mock(PullImageCmd.class);
    when(docker.pullImageCmd(anyString())).thenReturn(pullCommand);
    doReturn(pull).when(pullCommand).exec(any(PullImageResultCallback.class));
    var request = request(Set.of("/data"), Set.of(8080), Set.of("/dev/video0"), Set.of("/data"));
    assertThatThrownBy(() -> engine.create(request)).isInstanceOf(IllegalStateException.class);
    when(pull.awaitCompletion(anyLong(), any(TimeUnit.class)))
        .thenThrow(new InterruptedException());
    try {
      assertThatThrownBy(() -> engine.create(request)).isInstanceOf(IllegalStateException.class);
      assertThat(Thread.interrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    doReturn(true).when(pull).awaitCompletion(anyLong(), any(TimeUnit.class));
    when(docker.inspectImageCmd(anyString()).exec().getId()).thenReturn(null, " ");
    assertThatThrownBy(() -> engine.create(request)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> engine.create(request)).isInstanceOf(IllegalStateException.class);
    when(docker.inspectImageCmd(anyString()).exec().getId()).thenReturn("sha256:resolved");
    CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
    when(docker.createContainerCmd(anyString())).thenReturn(create);
    CreateContainerResponse response = mock(CreateContainerResponse.class);
    when(create.exec()).thenReturn(response);
    when(response.getId()).thenReturn(null);
    assertThatThrownBy(() -> engine.create(request)).isInstanceOf(IllegalStateException.class);
    when(response.getId()).thenReturn(" ");
    assertThatThrownBy(() -> engine.create(request)).isInstanceOf(IllegalStateException.class);
    when(response.getId()).thenReturn("container-id");
    engine.create(request);
    verify(create, atLeastOnce()).withName(ID);
    verify(create, atLeastOnce())
        .withLabels(
            argThat(labels -> "owner".equals(labels.get(DockerManagedServiceEngine.OWNER_LABEL))));
    var host = DockerManagedServiceEngine.hostConfig(request);
    assertThat(host.getBinds()).hasSize(1);
    assertThat(host.getDevices()).hasSize(1);
    assertThat(host.getPortBindings().getBindings()).hasSize(1);
    assertThatThrownBy(
            () ->
                DockerManagedServiceEngine.hostConfig(
                    request(Set.of("/data"), Set.of(), Set.of(), Set.of())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void boundsDiagnosticLogsAndIgnoresLogTransportFailures() {
    DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    var engine = new DockerManagedServiceEngine(docker, Duration.ofSeconds(1));
    InspectContainerResponse container = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
    InspectContainerCmd inspect = docker.inspectContainerCmd(ID);
    doReturn(container).when(inspect).exec();
    when(container.getConfig().getLabels())
        .thenReturn(Map.of(DockerManagedServiceEngine.SERVICE_LABEL, ID));
    LogContainerCmd logs = mock(LogContainerCmd.class, RETURNS_SELF);
    when(docker.logContainerCmd(ID)).thenReturn(logs);
    doAnswer(
            call -> {
              ResultCallback<Frame> callback = call.getArgument(0);
              callback.onNext(null);
              callback.onNext(new Frame(StreamType.STDOUT, null));
              callback.onNext(new Frame(StreamType.STDOUT, "x".repeat(2100).getBytes()));
              callback.onNext(new Frame(StreamType.STDERR, "last".getBytes()));
              callback.onNext(new Frame(StreamType.STDOUT, "ignored".getBytes()));
              callback.onComplete();
              return callback;
            })
        .when(logs)
        .exec(any());
    assertThat(engine.recentLogs(ID, 2)).containsExactly("x".repeat(2000), "last");
    doThrow(new IllegalStateException("broken")).when(logs).exec(any());
    assertThat(engine.recentLogs(ID, 2)).isEmpty();
  }

  private static ManagedServiceEngine.Request request(
      Set<String> paths, Set<Integer> ports, Set<String> devices, Set<String> grantedPaths) {
    var limits = new ManagedServiceLimits(1000, 1024, 10);
    var desired =
        new ManagedServiceDesiredState(
            "resource",
            "image@sha256:" + "a".repeat(64),
            "rev",
            ManagedServiceLifecycle.RUNNING,
            Set.of(),
            paths,
            ports,
            devices,
            limits,
            Duration.ofSeconds(1),
            0);
    var grant =
        new ManagedServiceResourceGrant(
            "owner", Set.of(), grantedPaths, ports, devices, limits, Duration.ofSeconds(1), 0);
    return new ManagedServiceEngine.Request(ID, desired, grant, "data");
  }
}

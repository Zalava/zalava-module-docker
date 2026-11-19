package org.zalava.modules.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.zalava.api.extensions.managed.ManagedOcrService;
import org.zalava.api.extensions.managed.ManagedServiceDesiredState;
import org.zalava.api.extensions.managed.ManagedServiceEngine;
import org.zalava.api.extensions.managed.ManagedServiceLifecycle;
import org.zalava.api.extensions.managed.ManagedServiceLimits;
import org.zalava.api.extensions.managed.ManagedServiceResourceGrant;

class DockerManagedOcrTransportTest {
  private static final String ID = ManagedOcrService.SERVICE_ID;
  private static final String IMAGE =
      "ghcr.io/zalava/zalava-tika-ocr-worker@sha256:" + "a".repeat(64);
  private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
  private final DockerManagedServiceEngine engine =
      new DockerManagedServiceEngine(docker, Duration.ofSeconds(3));
  private final ManagedOcrService.Request request =
      new ManagedOcrService.Request("image/png", "eng", new byte[] {1, 2});

  @Test
  void usesTheVerifiedContainerIdAndBoundsTheResponse() {
    readyWorker();
    when(docker
            .execCreateCmd("immutable-id")
            .withAttachStdin(true)
            .withAttachStdout(true)
            .withCmd("python", "/app/managed_ocr.py")
            .exec()
            .getId())
        .thenReturn("exec-id");
    ExecStartCmd start = mock(ExecStartCmd.class);
    when(docker.execStartCmd("exec-id")).thenReturn(start);
    when(start.withStdIn(any()))
        .thenAnswer(
            call -> {
              var input = call.getArgument(0, java.io.InputStream.class);
              assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                  .startsWith("{\"length\":2,\"mediaType\":\"image/png\",\"languages\":\"eng\"}\n");
              return start;
            });
    when(docker.inspectExecCmd("exec-id").exec().getExitCodeLong()).thenReturn(0L);
    sendResponse(start, "{\"text\":\"scan\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(engine.extractOcr(worker(), request)).contains("{\"text\":\"scan\"}");
    sendResponse(start, new byte[700_000]);
    assertThat(engine.extractOcr(worker(), request)).isEmpty();
    when(docker.inspectExecCmd("exec-id").exec().getExitCodeLong()).thenReturn(1L);
    assertThat(engine.extractOcr(worker(), request)).isEmpty();
  }

  @Test
  void withholdsDocumentFromStoppedUnhealthyOrReplacedContainers() {
    readyWorker();
    var container = docker.inspectContainerCmd(ID).exec();
    when(container.getState().getRunning()).thenReturn(false);
    assertThat(engine.extractOcr(worker(), request)).isEmpty();
    when(container.getState().getRunning()).thenReturn(true);
    when(container.getState().getHealth().getStatus()).thenReturn("unhealthy");
    assertThat(engine.extractOcr(worker(), request)).isEmpty();
    when(container.getState().getHealth().getStatus()).thenReturn("healthy");
    when(container.getImageId()).thenReturn("different");
    assertThat(engine.extractOcr(worker(), request)).isEmpty();
    verify(docker, never()).execCreateCmd(any());
  }

  @Test
  void rejectsMismatchedOwnershipAndWorkerIdentity() {
    readyWorker();
    var container = docker.inspectContainerCmd(ID).exec();
    var approved = worker();
    when(container.getConfig().getLabels())
        .thenReturn(
            Map.of(
                DockerManagedServiceEngine.SERVICE_LABEL, ID,
                DockerManagedServiceEngine.OWNER_LABEL, "wrong-owner",
                DockerManagedServiceEngine.DATA_IDENTITY_LABEL, "data-id"));
    assertThat(engine.extractOcr(approved, request)).isEmpty();
    when(container.getConfig().getLabels())
        .thenReturn(
            Map.of(
                DockerManagedServiceEngine.SERVICE_LABEL, ID,
                DockerManagedServiceEngine.OWNER_LABEL, "zalava-module-tika",
                DockerManagedServiceEngine.DATA_IDENTITY_LABEL, "old-data-id"));
    assertThat(engine.extractOcr(approved, request)).isEmpty();
    var wrongId =
        new ManagedServiceEngine.Request(
            "unrelated", approved.desiredState(), approved.grant(), approved.ownedDataIdentity());
    assertThat(engine.extractOcr(wrongId, request)).isEmpty();
    verify(docker, never()).execCreateCmd(any());
  }

  private void readyWorker() {
    var container = docker.inspectContainerCmd(ID).exec();
    when(container.getConfig().getLabels())
        .thenReturn(
            Map.of(
                DockerManagedServiceEngine.SERVICE_LABEL, ID,
                DockerManagedServiceEngine.OWNER_LABEL, "zalava-module-tika",
                DockerManagedServiceEngine.DATA_IDENTITY_LABEL, "data-id"));
    when(container.getHostConfig().getNetworkMode()).thenReturn("none");
    when(container.getNetworkSettings().getNetworks())
        .thenReturn(Map.of("none", mock(ContainerNetwork.class)));
    when(container.getState().getRunning()).thenReturn(true);
    when(container.getState().getHealth().getStatus()).thenReturn("healthy");
    when(container.getImageId()).thenReturn("image-id");
    when(container.getId()).thenReturn("immutable-id");
    when(docker.inspectImageCmd(IMAGE).exec().getId()).thenReturn("image-id");
  }

  private static void sendResponse(ExecStartCmd command, byte[] stdout) {
    doAnswer(
            call -> {
              ResultCallback<Frame> callback = call.getArgument(0);
              callback.onStart(() -> {});
              callback.onNext(new Frame(StreamType.STDERR, new byte[] {1}));
              callback.onNext(new Frame(StreamType.STDOUT, stdout));
              callback.onComplete();
              return callback;
            })
        .when(command)
        .exec(any());
  }

  private static ManagedServiceEngine.Request worker() {
    var limits = new ManagedServiceLimits(1000, 1024, 10);
    var deadline = Duration.ofSeconds(30);
    var state =
        new ManagedServiceDesiredState(
            ID,
            IMAGE,
            "1",
            ManagedServiceLifecycle.RUNNING,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            limits,
            deadline,
            3,
            true);
    var grant =
        new ManagedServiceResourceGrant(
            "zalava-module-tika", Set.of(), Set.of(), Set.of(), Set.of(), limits, deadline, 3);
    return new ManagedServiceEngine.Request(ID, state, grant, "data-id");
  }
}

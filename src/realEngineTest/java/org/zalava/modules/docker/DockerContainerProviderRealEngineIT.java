package org.zalava.modules.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.zalava.api.InvocationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real Engine proof for the user-facing provider; every created resource is removed in {@code
 * finally}.
 */
@Tag("docker-engine")
class DockerContainerProviderRealEngineIT {
  private static final String NAME = "user-it-docker-provider";
  private static final int HOST_PORT = 16379;
  private static final String IMAGE =
      "redis@sha256:12da49daa000c2be4d55118574f889c8cc298140ecf343d04f345c818f227823";
  private static final InvocationContext CONFIRMED = new InvocationContext("test", true, Map.of());
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void createsListsLogsStopsAndRemovesALoopbackPublishedContainer() throws Exception {
    removeIfPresent();
    try (DockerContainerProvider provider =
        new DockerContainerProvider(client(), true, false, false)) {
      provider.callTool(
          "runContainer",
          JSON.readTree(
              """
          {"name":"user-it-docker-provider","image":"%s","ports":[
            {"containerPort":6379,"hostPort":16379,"hostIp":"127.0.0.1"}]}
          """
                  .formatted(IMAGE)),
          CONFIRMED);

      try (DockerClient docker = client()) {
        var inspect = docker.inspectContainerCmd(NAME).exec();
        assertThat(inspect.getState().getRunning()).isTrue();
        assertThat(inspect.getConfig().getLabels())
            .containsEntry(DockerContainerProvider.OWNER_LABEL, DockerProviderFactory.ID);
        assertThat(
                inspect
                    .getHostConfig()
                    .getPortBindings()
                    .getBindings()
                    .get(com.github.dockerjava.api.model.ExposedPort.tcp(6379))[0]
                    .getHostIp())
            .isEqualTo("127.0.0.1");
      }
      assertThat(
              provider
                  .callTool(
                      "listContainers", JSON.readTree("{\"all\":true}"), InvocationContext.system())
                  .content()
                  .toString())
          .contains(NAME, "127.0.0.1");
      assertThat(
              provider
                  .callTool(
                      "containerLogs",
                      JSON.readTree(
                          """
          {"container":"user-it-docker-provider","lines":1}
          """),
                      InvocationContext.system())
                  .success())
          .isTrue();
      provider.callTool(
          "stopContainer",
          JSON.readTree(
              """
          {"container":"user-it-docker-provider"}
          """),
          CONFIRMED);
      provider.callTool(
          "removeContainer",
          JSON.readTree(
              """
          {"container":"user-it-docker-provider"}
          """),
          CONFIRMED);
      try (DockerClient docker = client()) {
        assertThat(
                docker.listContainersCmd().withShowAll(true).exec().stream()
                    .map(container -> container.getNames()[0]))
            .doesNotContain("/" + NAME);
      }
    } finally {
      removeIfPresent();
    }
  }

  private static DockerClient client() {
    var config =
        DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost("unix:///var/run/docker.sock")
            .build();
    return DockerClientImpl.getInstance(
        config,
        new ApacheDockerHttpClient.Builder()
            .dockerHost(URI.create("unix:///var/run/docker.sock"))
            .build());
  }

  private static void removeIfPresent() throws IOException {
    try (DockerClient docker = client()) {
      try {
        docker.removeContainerCmd(NAME).withForce(true).withRemoveVolumes(false).exec();
      } catch (com.github.dockerjava.api.exception.NotFoundException ignored) {
        // The test may have failed before creation.
      }
    }
  }
}

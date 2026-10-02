package org.zalava.modules.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zalava.api.InvocationContext;
import org.zalava.api.ZalavaToolDescriptor;
import org.zalava.api.testing.ConfigFixture;
import org.zalava.api.testing.ModuleContractKit;
import org.zalava.api.testing.ProviderFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Built-JAR user-tool journeys against an HTTP Docker Engine fixture, not real-SEA acceptance. */
class DockerContainerContractTest {
  private static final String MODULE = "zalava-module-docker";
  private static final String PROVIDER = "docker-containers";
  private static final InvocationContext CONFIRMED =
      new InvocationContext("operator", true, Map.of());
  private final JsonMapper json = JsonMapper.builder().build();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private ModuleContractKit kit;
  private HttpServer server;
  private volatile JsonNode created;
  private volatile boolean running;
  private volatile boolean exists;
  private volatile boolean failStart;
  private volatile String labels = "{\"org.zalava.user.provider-id\":\"docker-containers\"}";

  @BeforeEach
  void setup() throws IOException {
    kit =
        ModuleContractKit.load(
            Path.of(System.getProperty("module.artifact")),
            List.of(),
            MODULE,
            System.getProperty("module.version"));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::respond);
    server.start();
  }

  @AfterEach
  void close() throws Exception {
    server.stop(0);
    kit.close();
  }

  @Test
  void absentConfigurationDoesNotEnableUserTools() {
    try (var providers = kit.providers(ConfigFixture.empty())) {
      assertThat(providers.providers()).isEmpty();
    }
    assertThat(requests).isEmpty();
  }

  @Test
  void listsAndInspectsWithoutExposingEnvironmentSecrets() {
    exists = true;
    try (var providers = providers(false, false, false)) {
      assertThat(providers.tools(PROVIDER).stream().map(ZalavaToolDescriptor::name))
          .containsExactly("listContainers", "inspectContainer", "containerLogs");
      var inventory =
          providers.invoke(
              PROVIDER,
              "listContainers",
              new tools.jackson.databind.json.JsonMapper()
                  .convertValue(
                      args("{\"all\":true}"),
                      new tools.jackson.core.type.TypeReference<
                          java.util.Map<String, Object>>() {}));
      assertThat(inventory.success()).isTrue();
      assertThat(inventory.content().toString()).contains("sample", "8080", "sea-user");
      var detail =
          providers.invoke(
              PROVIDER,
              "inspectContainer",
              new tools.jackson.databind.json.JsonMapper()
                  .convertValue(
                      args("{\"container\":\"sample\"}"),
                      new tools.jackson.core.type.TypeReference<
                          java.util.Map<String, Object>>() {}));
      assertThat(detail.content().toString()).contains("sample", "8080").doesNotContain("SECRET");
      assertThat(requests).anyMatch(value -> value.contains("all=true") || value.contains("all=1"));
      assertThatThrownBy(
              () ->
                  providers
                      .requireProvider(PROVIDER)
                      .callTool(
                          "stopContainer",
                          new tools.jackson.databind.json.JsonMapper()
                              .convertValue(
                                  args("{\"container\":\"sample\"}"),
                                  new tools.jackson.core.type.TypeReference<
                                      java.util.Map<String, Object>>() {}),
                          CONFIRMED))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("read-only");
    }
  }

  @Test
  void createsPublishesAndRunsLifecycleUsingResolvedContainerIds() {
    try (var providers = providers(true, false, false)) {
      assertThat(
              providers.tools(PROVIDER).stream()
                  .filter(ZalavaToolDescriptor::sideEffecting)
                  .map(ZalavaToolDescriptor::name))
          .containsExactly(
              "runContainer",
              "startContainer",
              "stopContainer",
              "restartContainer",
              "removeContainer");
      var result =
          providers.invoke(
              PROVIDER,
              "runContainer",
              new tools.jackson.databind.json.JsonMapper()
                  .convertValue(
                      args(
                          """
          {"name":"sample","image":"nginx:alpine","ports":[
            {"containerPort":80,"hostPort":8080},
            {"containerPort":53,"hostPort":5353,"protocol":"udp"}]}
          """),
                      new tools.jackson.core.type.TypeReference<
                          java.util.Map<String, Object>>() {}),
              CONFIRMED);
      assertThat(result.content().toString()).contains("created-id", "started=true");
      assertThat(created.path("Image").asString()).isEqualTo("sha256:resolved");
      assertThat(created.path("Labels").path("org.zalava.user.provider-id").asString())
          .isEqualTo(PROVIDER);
      var host = created.path("HostConfig");
      assertThat(host.path("PortBindings").path("80/tcp").get(0).path("HostIp").asString())
          .isEqualTo("127.0.0.1");
      assertThat(host.path("PortBindings").path("53/udp").get(0).path("HostPort").asString())
          .isEqualTo("5353");
      assertThat(host.path("Memory").asLong()).isEqualTo(512L * 1024 * 1024);
      assertThatThrownBy(
              () ->
                  providers.invoke(
                      PROVIDER,
                      "removeContainer",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              target(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}),
                      CONFIRMED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Stop");
      providers.invoke(
          PROVIDER,
          "restartContainer",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  target(),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
          CONFIRMED);
      providers.invoke(
          PROVIDER,
          "stopContainer",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  target(),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
          CONFIRMED);
      assertThat(running).isFalse();
      providers.invoke(
          PROVIDER,
          "startContainer",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  target(),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
          CONFIRMED);
      assertThat(running).isTrue();
      providers.invoke(
          PROVIDER,
          "stopContainer",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  target(),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
          CONFIRMED);
      providers.invoke(
          PROVIDER,
          "removeContainer",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  target(),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
          CONFIRMED);
      assertThat(exists).isFalse();
      assertThat(requests).contains("DELETE /containers/created-id");
      assertThat(requests).noneMatch(value -> value.startsWith("POST /containers/sample/stop"));
    }
  }

  @Test
  void rejectsUnconfirmedAndInvalidRequestsBeforeAnyEngineCalls() {
    try (var providers = providers(true, false, false)) {
      assertThatThrownBy(
              () ->
                  providers
                      .requireProvider(PROVIDER)
                      .callTool(
                          "runContainer",
                          new tools.jackson.databind.json.JsonMapper()
                              .convertValue(
                                  args("{\"name\":\"sample\",\"image\":\"nginx\"}"),
                                  new tools.jackson.core.type.TypeReference<
                                      java.util.Map<String, Object>>() {}),
                          InvocationContext.system()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("confirmation");
      for (String invalid :
          List.of(
              "{\"name\":\"sea-managed\",\"image\":\"nginx\"}",
              "{\"name\":\"sample\",\"image\":\"nginx\",\"privileged\":true}",
              "{\"name\":\"sample\",\"image\":42}",
              "{\"name\":\"sample\",\"image\":\"nginx\",\"ports\":[{\"hostPort\":70000,\"containerPort\":80}]}",
              "{\"name\":\"sample\",\"image\":\"nginx\",\"ports\":[{\"hostPort\":8080,\"containerPort\":80,\"hostIp\":\"0.0.0.0\"}]}")) {
        assertThatThrownBy(
                () ->
                    providers.invoke(
                        PROVIDER,
                        "runContainer",
                        new tools.jackson.databind.json.JsonMapper()
                            .convertValue(
                                args(invalid),
                                new tools.jackson.core.type.TypeReference<
                                    java.util.Map<String, Object>>() {}),
                        CONFIRMED))
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThat(requests).isEmpty();
    }
  }

  @Test
  void protectsReconcilerOwnershipEvenWhenExternalManagementIsEnabled() {
    exists = true;
    labels = "{\"org.zalava.managed.service-id\":\"managed\"}";
    try (var providers = providers(true, true, true)) {
      for (String operation :
          List.of("startContainer", "stopContainer", "restartContainer", "removeContainer")) {
        assertThatThrownBy(
                () ->
                    providers.invoke(
                        PROVIDER,
                        operation,
                        new tools.jackson.databind.json.JsonMapper()
                            .convertValue(
                                target(),
                                new tools.jackson.core.type.TypeReference<
                                    java.util.Map<String, Object>>() {}),
                        CONFIRMED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reconciler-owned");
      }
    }
    assertThat(requests).allMatch(value -> value.startsWith("GET "));
  }

  @Test
  void externalContainerChangesNeedExplicitConfiguration() {
    exists = true;
    labels = "{}";
    try (var providers = providers(true, false, false)) {
      assertThatThrownBy(
              () ->
                  providers.invoke(
                      PROVIDER,
                      "startContainer",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              target(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}),
                      CONFIRMED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("manageExternalContainers");
      assertThat(requests).allMatch(value -> value.startsWith("GET "));
    }
    try (var providers = providers(true, true, false)) {
      providers.invoke(
          PROVIDER,
          "startContainer",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  target(),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
          CONFIRMED);
      assertThat(running).isTrue();
    }
  }

  @Test
  void publicPublishingNeedsExplicitConfigurationAndRequest() {
    try (var providers = providers(true, false, true)) {
      providers.invoke(
          PROVIDER,
          "runContainer",
          new tools.jackson.databind.json.JsonMapper()
              .convertValue(
                  args(
                      """
          {"name":"sample","image":"nginx","ports":[
            {"containerPort":80,"hostPort":8080,"hostIp":"0.0.0.0"}]}
          """),
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}),
          CONFIRMED);
      assertThat(
              created
                  .path("HostConfig")
                  .path("PortBindings")
                  .path("80/tcp")
                  .get(0)
                  .path("HostIp")
                  .asString())
          .isEqualTo("0.0.0.0");
    }
  }

  @Test
  void collisionDoesNotPullOrReplaceAndPartialStartFailureIdentifiesCreatedContainer() {
    exists = true;
    try (var providers = providers(true, false, false)) {
      assertThatThrownBy(
              () ->
                  providers.invoke(
                      PROVIDER,
                      "runContainer",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              args("{\"name\":\"sample\",\"image\":\"nginx\"}"),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}),
                      CONFIRMED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already exists");
      assertThat(requests).allMatch(value -> value.startsWith("GET "));
      exists = false;
      failStart = true;
      assertThatThrownBy(
              () ->
                  providers.invoke(
                      PROVIDER,
                      "runContainer",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              args("{\"name\":\"sample\",\"image\":\"nginx\"}"),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}),
                      CONFIRMED))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("created-id");
      assertThat(exists).isTrue();
      assertThat(running).isFalse();
    }
  }

  @Test
  void logsAreBoundedAndDoNotFollow() {
    exists = true;
    try (var providers = providers(false, false, false)) {
      var result =
          providers.invoke(
              PROVIDER,
              "containerLogs",
              new tools.jackson.databind.json.JsonMapper()
                  .convertValue(
                      args("{\"container\":\"sample\",\"lines\":1}"),
                      new tools.jackson.core.type.TypeReference<
                          java.util.Map<String, Object>>() {}));
      @SuppressWarnings("unchecked")
      var content = (Map<String, Object>) result.content();
      @SuppressWarnings("unchecked")
      var frames = (List<String>) content.get("frames");
      assertThat(frames).hasSize(1);
      assertThat(frames.getFirst()).hasSizeLessThanOrEqualTo(2000);
      assertThat(requests)
          .anyMatch(value -> value.contains("tail=1") && !value.contains("follow=true"));
    }
  }

  private ProviderFixture providers(boolean writable, boolean external, boolean publicPorts) {
    return kit.providers(
        ConfigFixture.empty()
            .factoryConfiguration(
                MODULE,
                PROVIDER,
                Map.of(
                    "engineEndpoint",
                    "tcp://127.0.0.1:" + server.getAddress().getPort(),
                    "writable",
                    writable,
                    "manageExternalContainers",
                    external,
                    "allowPublicPorts",
                    publicPorts)));
  }

  private JsonNode target() {
    return args("{\"container\":\"sample\"}");
  }

  private JsonNode args(String value) {
    return json.readTree(value);
  }

  private void respond(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath().replaceFirst("^/v[0-9.]+", "");
    String method = exchange.getRequestMethod();
    requests.add(
        method
            + " "
            + path
            + (exchange.getRequestURI().getRawQuery() == null
                ? ""
                : "?" + exchange.getRequestURI().getRawQuery()));
    int status = 200;
    String body = "{}";
    if (path.equals("/containers/json")) {
      body =
          "[{\"Id\":\"created-id\",\"Names\":[\"/sample\"],\"Image\":\"nginx\",\"State\":\"running\",\"Status\":\"Up\",\"Labels\":"
              + labels
              + ",\"Ports\":[{\"IP\":\"127.0.0.1\",\"PrivatePort\":80,\"PublicPort\":8080,\"Type\":\"tcp\"}]}]";
    } else if (path.matches("/containers/[^/]+/json")) {
      if (!exists) {
        status = 404;
        body = "{\"message\":\"not found\"}";
      } else
        body =
            "{\"Id\":\"created-id\",\"Name\":\"/sample\",\"Config\":{\"Image\":\"nginx\",\"Env\":[\"SECRET=hidden\"],\"Labels\":"
                + labels
                + "},\"State\":{\"Running\":"
                + running
                + ",\"Status\":\"running\"},\"HostConfig\":{\"PortBindings\":{\"80/tcp\":[{\"HostIp\":\"127.0.0.1\",\"HostPort\":\"8080\"}]}}}";
    } else if (path.equals("/images/create")) {
      body = "{\"status\":\"Download complete\"}\n";
    } else if (path.startsWith("/images/") && path.endsWith("/json")) {
      body = "{\"Id\":\"sha256:resolved\"}";
    } else if (path.equals("/containers/create")) {
      created = json.readTree(exchange.getRequestBody().readAllBytes());
      exists = true;
      status = 201;
      body = "{\"Id\":\"created-id\",\"Warnings\":[]}";
    } else if (path.endsWith("/start") || path.endsWith("/restart")) {
      if (failStart) {
        status = 500;
        body = "{\"message\":\"port already allocated\"}";
      } else {
        running = true;
        status = 204;
      }
    } else if (path.endsWith("/stop")) {
      running = false;
      status = 204;
    } else if (method.equals("DELETE")) {
      exists = false;
      status = 204;
    } else if (path.endsWith("/logs")) {
      byte[] payload = "x".repeat(3000).getBytes(StandardCharsets.UTF_8);
      var bytes = java.nio.ByteBuffer.allocate(8 + payload.length);
      bytes.put(new byte[] {1, 0, 0, 0}).putInt(payload.length).put(payload);
      exchange.getResponseHeaders().set("Content-Type", "application/vnd.docker.raw-stream");
      exchange.sendResponseHeaders(200, bytes.array().length);
      exchange.getResponseBody().write(bytes.array());
      exchange.close();
      return;
    } else {
      status = 404;
      body = "{\"message\":\"unexpected endpoint\"}";
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}

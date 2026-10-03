package org.zalava.modules.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zalava.api.ModuleDescriptor;
import org.zalava.api.ZalavaServiceFactory;
import org.zalava.api.extensions.managed.ManagedServiceEngine;
import org.zalava.api.testing.ConfigFixture;
import org.zalava.api.testing.ModuleContractKit;
import org.zalava.api.testing.ServiceFixture;

/**
 * Exercises the real built module JAR at the stable {@code module-api} boundary through the
 * released contract kit. Host-owned resolution, validation, approval, persistence and transport
 * stay covered by Zalava; the kit asserts only the module-owned service surface.
 */
class DockerZalavaModuleTest {

  private static final String MODULE_ID = "zalava-module-docker";
  private static final String SERVICE_ID = "managed-service-engine";

  private ModuleContractKit kit;

  @BeforeEach
  void loadTheBuiltArtifact() {
    Path artifact = Path.of(System.getProperty("module.artifact"));
    String version = System.getProperty("module.version");
    kit = ModuleContractKit.load(artifact, List.of(), MODULE_ID, version);
  }

  @AfterEach
  void closeTheArtifact() throws Exception {
    if (kit != null) {
      kit.close();
    }
  }

  @Test
  void loadsTheModuleFromTheBuiltArtifact() {
    assertThat(kit.module().getClass().getClassLoader()).isNotSameAs(getClass().getClassLoader());
    assertThat(
            kit.module().getClass().getProtectionDomain().getCodeSource().getLocation().toString())
        .endsWith(".jar");
  }

  @Test
  void exposesTheModuleOwnedDescriptor() {
    ModuleDescriptor descriptor = kit.module().descriptor();
    assertThat(kit.moduleId()).isEqualTo(MODULE_ID);
    assertThat(kit.version()).isEqualTo(System.getProperty("module.version"));
    assertThat(descriptor.displayName()).isEqualTo("Docker Services");
    assertThat(descriptor.description()).isNotBlank();
  }

  @Test
  void declaresAnOptInProviderAlongsideTheEngine() {
    assertThat(kit.module().providerFactories()).hasSize(1);
    assertThat(kit.module().providerFactories().getFirst().descriptor().factoryId())
        .isEqualTo("docker-containers");
    assertThat(kit.module().webExtensions()).isEmpty();
    assertThat(kit.module().serviceRequirements()).isEmpty();
    assertThat(kit.module().serviceFactories()).hasSize(1);
  }

  @Test
  void declaresTheManagedServiceEngineContractVersionTwo() {
    ZalavaServiceFactory<?> factory = kit.module().serviceFactories().getFirst();
    assertThat(factory.descriptor().serviceId()).isEqualTo(SERVICE_ID);
    assertThat(factory.descriptor().moduleId()).isEqualTo(MODULE_ID);
    assertThat(factory.descriptor().contractVersion()).isEqualTo("2");
    assertThat(factory.contract().serviceId()).isEqualTo(SERVICE_ID);
    assertThat(factory.contract().contractVersion()).isEqualTo("2");
    assertThat(factory.contract().serviceType()).isEqualTo(ManagedServiceEngine.class);
  }

  @Test
  void declaresTheStableServicesConfigurationScopeForTheManagedEngine() {
    @SuppressWarnings("unchecked")
    Map<String, Object> properties =
        (Map<String, Object>) kit.module().configuration().jsonSchema().get("properties");

    assertThat(properties)
        .containsKey("services")
        .doesNotContainKey("docker-managed-service-engine");
  }

  @Test
  void createsTheTypedEngineFromExplicitConfiguration() throws Exception {
    try (ServiceFixture services = kit.services(configuration())) {
      ManagedServiceEngine engine = services.service(ManagedServiceEngine.CONTRACT);

      assertThat(engine).isInstanceOf(ManagedServiceEngine.class);
      // The built artifact must implement the full v2 surface, including the removal
      // primitive, without a live engine.
      assertThat(engine.getClass().getMethod("remove", String.class)).isNotNull();
      assertThat(services.factories()).hasSize(1);
    }
  }

  @Test
  void rejectsConfigurationWithoutAnExplicitEngineEndpoint() {
    assertThatThrownBy(() -> kit.services(ConfigFixture.empty()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("engineEndpoint");
  }

  private static ConfigFixture configuration() {
    return ConfigFixture.empty()
        .serviceConfiguration(
            MODULE_ID,
            Map.of("engineEndpoint", "tcp://127.0.0.1:2375", "commandTimeoutSeconds", 7));
  }
}

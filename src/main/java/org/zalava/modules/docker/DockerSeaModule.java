package org.zalava.modules.docker;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.zalava.api.ModuleConfigurationDescriptor;
import org.zalava.api.ModuleDescriptor;
import org.zalava.api.ProviderFactory;
import org.zalava.api.ZalavaModule;
import org.zalava.api.ZalavaServiceFactory;

/** Docker engine service and independently configured user-facing container tools. */
public final class DockerSeaModule implements ZalavaModule {

  public static final String MODULE_ID = "zalava-module-docker";

  @Override
  public ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        MODULE_ID,
        version(),
        "Docker Services",
        "Bounded Docker Engine lifecycle and opt-in user-requested container management");
  }

  @Override
  public List<ProviderFactory> providerFactories() {
    return List.of(new DockerProviderFactory());
  }

  @Override
  public ModuleConfigurationDescriptor configuration() {
    return new ModuleConfigurationDescriptor(
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            Map.of(
                "services",
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    false,
                    "required",
                    List.of("engineEndpoint"),
                    "properties",
                    Map.of(
                        "engineEndpoint", Map.of("type", "string", "minLength", 1),
                        "commandTimeoutSeconds", Map.of("type", "integer", "minimum", 1))),
                DockerProviderFactory.ID,
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    false,
                    "required",
                    List.of("engineEndpoint"),
                    "properties",
                    Map.of(
                        "engineEndpoint", Map.of("type", "string", "minLength", 1),
                        "writable", Map.of("type", "boolean", "default", false),
                        "manageExternalContainers", Map.of("type", "boolean", "default", false),
                        "allowPublicPorts", Map.of("type", "boolean", "default", false))))));
  }

  @Override
  public List<ZalavaServiceFactory<?>> serviceFactories() {
    return List.of(new DockerManagedServiceEngineFactory());
  }

  static String version() {
    Properties properties = new Properties();
    try (InputStream input = DockerSeaModule.class.getResourceAsStream("/module.properties")) {
      if (input == null) {
        throw new IllegalStateException("Missing module version metadata");
      }
      properties.load(input);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read module version metadata", exception);
    }
    return properties.getProperty("module.version");
  }
}

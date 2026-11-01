package org.zalava.modules.docker;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.zalava.api.ModuleConfigurationDescriptor;
import org.zalava.api.ModuleDescriptor;
import org.zalava.api.ProviderFactory;
import org.zalava.api.ZalavaModule;
import org.zalava.api.ZalavaServiceFactory;

/** Docker engine service and independently configured user-facing container tools. */
public final class DockerZalavaModule implements ZalavaModule {

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
            ordered(
                Map.of(
                    "services",
                    Map.of(
                        "title",
                        "Services used by Zalava modules",
                        "description",
                        "Run approved services that modules need, such as Home Assistant.",
                        "type",
                        "object",
                        "additionalProperties",
                        false,
                        "required",
                        List.of("engineEndpoint"),
                        "properties",
                        ordered(
                            Map.of(
                                "engineEndpoint",
                                    Map.of(
                                        "type",
                                        "string",
                                        "minLength",
                                        1,
                                        "examples",
                                        List.of("unix:///var/run/docker.sock"),
                                        "title",
                                        "Docker connection for managed services",
                                        "description",
                                        "Used to run approved services needed by modules, such as Home Assistant. Connecting grants Zalava powerful access to this Docker engine."),
                                "commandTimeoutSeconds",
                                    Map.of(
                                        "type",
                                        "integer",
                                        "minimum",
                                        1,
                                        "title",
                                        "Service operation timeout (seconds)",
                                        "description",
                                        "Leave blank to use 30 seconds.")),
                            "engineEndpoint",
                            "commandTimeoutSeconds")),
                    DockerProviderFactory.ID,
                    Map.of(
                        "title",
                        "User-requested container tools",
                        "description",
                        "Optional tools for viewing or managing containers. These are separate from managed services.",
                        "type",
                        "object",
                        "additionalProperties",
                        false,
                        "properties",
                        ordered(
                            Map.of(
                                "engineEndpoint",
                                    Map.of(
                                        "type",
                                        "string",
                                        "minLength",
                                        1,
                                        "examples",
                                        List.of("unix:///var/run/docker.sock"),
                                        "title",
                                        "Docker connection for container tools",
                                        "description",
                                        "Optional: enter an engine address to enable user-requested container tools. It can be the same engine as managed services. Viewing may expose container details and logs."),
                                "writable",
                                    Map.of(
                                        "type",
                                        "boolean",
                                        "default",
                                        false,
                                        "title",
                                        "Allow container tools to make changes",
                                        "description",
                                        "Enables requested container changes subject to confirmation. Managed services do not need this option."),
                                "manageExternalContainers",
                                    Map.of(
                                        "type",
                                        "boolean",
                                        "default",
                                        false,
                                        "title",
                                        "Allow changes to other containers",
                                        "description",
                                        "Allows container tools to change containers not created by Zalava user requests."),
                                "allowPublicPorts",
                                    Map.of(
                                        "type",
                                        "boolean",
                                        "default",
                                        false,
                                        "title",
                                        "Allow container tools to expose ports publicly",
                                        "description",
                                        "Allows container tools to make ports accessible beyond this computer.")),
                            "engineEndpoint",
                            "writable",
                            "manageExternalContainers",
                            "allowPublicPorts"))),
                "services",
                DockerProviderFactory.ID)));
  }

  private static Map<String, Object> ordered(Map<String, Object> properties, String... keys) {
    Map<String, Object> ordered = new LinkedHashMap<>();
    for (String key : keys) {
      ordered.put(key, properties.get(key));
    }
    return Collections.unmodifiableMap(ordered);
  }

  @Override
  public List<ZalavaServiceFactory<?>> serviceFactories() {
    return List.of(new DockerManagedServiceEngineFactory());
  }

  static String version() {
    Properties properties = new Properties();
    try (InputStream input = DockerZalavaModule.class.getResourceAsStream("/module.properties")) {
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

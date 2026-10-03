package org.zalava.modules.docker;

import java.time.Duration;
import java.util.Map;
import org.zalava.api.ZalavaServiceContract;
import org.zalava.api.ZalavaServiceDescriptor;
import org.zalava.api.ZalavaServiceFactory;
import org.zalava.api.ZalavaServiceFactoryContext;
import org.zalava.api.extensions.managed.ManagedServiceEngine;

/**
 * Creates the Docker engine service from explicit module configuration only. The engine endpoint is
 * Zalava administrator configuration; it is never derived from module input, caller requests, or
 * environment fallbacks.
 */
final class DockerManagedServiceEngineFactory
    implements ZalavaServiceFactory<ManagedServiceEngine> {

  static final String ENDPOINT_PROPERTY = "engineEndpoint";
  static final String TIMEOUT_PROPERTY = "commandTimeoutSeconds";
  static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(30);

  @Override
  public ZalavaServiceDescriptor descriptor() {
    return new ZalavaServiceDescriptor(
        ManagedServiceEngine.CONTRACT.serviceId(),
        DockerZalavaModule.MODULE_ID,
        ManagedServiceEngine.CONTRACT.contractVersion());
  }

  @Override
  public ZalavaServiceContract<ManagedServiceEngine> contract() {
    return ManagedServiceEngine.CONTRACT;
  }

  @Override
  public ManagedServiceEngine create(ZalavaServiceFactoryContext context) {
    Map<String, Object> configuration = context.configuration();
    Object endpoint = configuration.get(ENDPOINT_PROPERTY);
    if (!(endpoint instanceof String engineEndpoint) || engineEndpoint.isBlank()) {
      throw new IllegalStateException(
          "Module "
              + context.moduleId()
              + " requires explicit "
              + ENDPOINT_PROPERTY
              + " configuration");
    }
    Duration commandTimeout = DEFAULT_COMMAND_TIMEOUT;
    Object timeout = configuration.get(TIMEOUT_PROPERTY);
    if (timeout instanceof Number seconds) {
      commandTimeout = Duration.ofSeconds(seconds.longValue());
    } else if (timeout instanceof String text && !text.isBlank()) {
      commandTimeout = Duration.ofSeconds(Long.parseLong(text.trim()));
    }
    return new DockerManagedServiceEngine(engineEndpoint.trim(), commandTimeout);
  }
}

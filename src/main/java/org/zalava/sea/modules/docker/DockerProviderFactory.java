package org.zalava.modules.docker;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.zalava.ProviderFactory;
import org.zalava.ProviderFactoryContext;
import org.zalava.ProviderFactoryDescriptor;
import org.zalava.SeaProvider;

/** User tools are configured independently from the reconciler's engine service. */
final class DockerProviderFactory implements ProviderFactory {
  static final String ID = "docker-containers";

  @Override
  public ProviderFactoryDescriptor descriptor() {
    return new ProviderFactoryDescriptor(ID, DockerSeaModule.MODULE_ID, ID,
        "Docker Containers", "User-requested Docker container management.");
  }

  @Override
  public List<SeaProvider> createProviders(ProviderFactoryContext context) {
    Map<String, Object> configuration = context.configuration();
    if (configuration.isEmpty()) return List.of();
    Object endpoint = configuration.get("engineEndpoint");
    if (!(endpoint instanceof String address) || address.isBlank()) {
      throw new IllegalArgumentException("Docker provider requires an explicit engineEndpoint");
    }
    boolean writable = flag(configuration, "writable");
    boolean external = flag(configuration, "manageExternalContainers");
    boolean publicPorts = flag(configuration, "allowPublicPorts");
    var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(address.trim()).build();
    var transport = new ApacheDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig())
        .connectionTimeout(Duration.ofSeconds(10)).responseTimeout(Duration.ofSeconds(30)).build();
    return List.of(new DockerContainerProvider(
        DockerClientImpl.getInstance(config, transport), writable, external, publicPorts));
  }

  private static boolean flag(Map<String, Object> configuration, String key) {
    Object value = configuration.getOrDefault(key, false);
    if (!(value instanceof Boolean flag)) {
      throw new IllegalArgumentException(key + " must be boolean");
    }
    return flag;
  }
}

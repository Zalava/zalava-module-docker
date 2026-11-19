package org.zalava.modules.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.zalava.api.extensions.managed.ManagedOcrService;
import org.zalava.api.extensions.managed.ManagedServiceDesiredState;
import org.zalava.api.extensions.managed.ManagedServiceEngine;
import org.zalava.api.extensions.managed.ManagedServiceLifecycle;
import org.zalava.api.extensions.managed.ManagedServiceLimits;
import org.zalava.api.extensions.managed.ManagedServiceResourceGrant;

@Tag("docker-engine")
class DockerManagedOcrRealEngineIT {
  @Test
  void extractsTextFromTheReleasedIsolatedWorker() throws Exception {
    String id = ManagedOcrService.SERVICE_ID;
    var engine =
        new DockerManagedServiceEngine("unix:///var/run/docker.sock", Duration.ofSeconds(30));
    var limits = new ManagedServiceLimits(1000, 512L * 1024 * 1024, 64);
    var deadline = Duration.ofSeconds(40);
    var state =
        new ManagedServiceDesiredState(
            id,
            "ghcr.io/zalava/zalava-tika-ocr-worker@sha256:ec6134c799a81b0239b850df1c6698b8bcfee2632cae95b769bad77f86ca3c76",
            "0.2.0",
            ManagedServiceLifecycle.RUNNING,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            limits,
            deadline,
            2,
            true);
    var grant =
        new ManagedServiceResourceGrant(
            "zalava-module-tika", Set.of(), Set.of(), Set.of(), Set.of(), limits, deadline, 2);
    var worker = new ManagedServiceEngine.Request(id, state, grant, "ocr-engine-test");
    var request = new ManagedOcrService.Request("image/png", "eng", image());

    assertThat(engine.extractOcr(worker, request)).isEmpty();
    engine.create(worker);
    try {
      engine.start(id);
      long expires = System.nanoTime() + deadline.toNanos();
      while (!engine.inspect(id).ready() && System.nanoTime() < expires) {
        Thread.sleep(250);
      }
      assertThat(engine.isNetworkIsolated(id)).isTrue();
      assertThat(engine.extractOcr(worker, request))
          .hasValueSatisfying(result -> assertThat(result).contains("SCAN TEST 123"));
    } finally {
      engine.remove(id);
    }
  }

  private static byte[] image() throws Exception {
    var image = new BufferedImage(600, 100, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 600, 100);
      graphics.setColor(Color.BLACK);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
      graphics.drawString("SCAN TEST 123", 20, 65);
    } finally {
      graphics.dispose();
    }
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }
}

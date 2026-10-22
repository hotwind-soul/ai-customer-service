package com.example.aics.channel;

import com.example.aics.image.ImageService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WeComImageSenderTest {

    @Test
    void springUsesTheProductionConstructorWhenTheTestConstructorAlsoExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WeComKfTokenService.class,
                    () -> mock(WeComKfTokenService.class));
            context.registerBean(ImageService.class, () -> mock(ImageService.class));
            context.register(WeComImageSender.class);

            context.refresh();

            assertThat(context.getBean(WeComImageSender.class)).isNotNull();
        }
    }

    @Test
    void imageMessageUsesWeComKfImagePayload() {
        Map<String, Object> payload = WeComImageSender.imageMessagePayload(
                "external-user", "kf-id", "media-id");

        assertThat(payload).containsEntry("touser", "external-user")
                .containsEntry("open_kfid", "kf-id")
                .containsEntry("msgtype", "image");
        assertThat(((Map<?, ?>) payload.get("image")).get("media_id")).isEqualTo("media-id");
    }

    @Test
    void multipartBodyContainsMediaFieldMetadataAndOriginalBytes() throws Exception {
        ImageService.OutboundImage image = new ImageService.OutboundImage(
                "asset.jpg", "image/jpeg", "binary-image".getBytes(StandardCharsets.UTF_8));

        String body = new String(WeComImageSender.multipartBody(image, "test-boundary"),
                StandardCharsets.ISO_8859_1);

        assertThat(body).contains("--test-boundary\r\n")
                .contains("name=\"media\"; filename=\"asset.jpg\"")
                .contains("Content-Type: image/jpeg")
                .contains("binary-image")
                .endsWith("--test-boundary--\r\n");
    }
}

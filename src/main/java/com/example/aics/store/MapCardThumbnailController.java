package com.example.aics.store;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;

/** 提供企微 link 消息可稳定抓取的 PNG 地图缩略图。 */
@RestController
public class MapCardThumbnailController {

    private static final byte[] THUMBNAIL = renderThumbnail();

    @GetMapping(value = "/assets/tencent-map-card.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> thumbnail() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(THUMBNAIL);
    }

    private static byte[] renderThumbnail() {
        BufferedImage image = new BufferedImage(480, 270, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(231, 247, 242));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());

            g.setColor(new Color(148, 205, 188));
            g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawRoad(g, -20, 67, 70, 38, 135, 96, 220, 64, 315, 30, 405, 32, 500, 62);
            drawRoad(g, -15, 208, 75, 162, 150, 225, 245, 187, 345, 146, 415, 157, 505, 181);
            drawRoad(g, 82, -20, 96, 54, 55, 108, 79, 171, 107, 227, 130, 249, 117, 292);
            drawRoad(g, 359, -18, 335, 47, 378, 95, 356, 151, 327, 205, 318, 239, 333, 289);

            Path2D.Double pin = new Path2D.Double();
            pin.moveTo(240, 246);
            pin.curveTo(226, 229, 154, 177, 154, 126);
            pin.curveTo(154, 78, 192, 40, 240, 40);
            pin.curveTo(288, 40, 326, 78, 326, 126);
            pin.curveTo(326, 177, 254, 229, 240, 246);
            pin.closePath();
            g.setColor(new Color(22, 167, 123));
            g.fill(pin);
            g.setColor(Color.WHITE);
            g.fillOval(205, 91, 70, 70);
            g.setColor(new Color(189, 232, 220));
            g.fillOval(224, 110, 32, 32);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("当前 Java 运行时不支持 PNG 编码");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void drawRoad(Graphics2D g, double x1, double y1, double x2, double y2,
                                 double x3, double y3, double x4, double y4,
                                 double x5, double y5, double x6, double y6,
                                 double x7, double y7) {
        Path2D.Double road = new Path2D.Double();
        road.moveTo(x1, y1);
        road.curveTo(x2, y2, x3, y3, x4, y4);
        road.curveTo(x5, y5, x6, y6, x7, y7);
        g.draw(road);
    }
}

package com.example.aics.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void 首次项目只选择启用效果图和当前有效优惠海报() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ImageService service = new ImageService(jdbc, tempDir.toString());
        LocalDateTime now = LocalDateTime.now();

        List<Map<String, Object>> rows = new ArrayList<>();
        // 模拟升级前的真实数据：优惠图仍被存成 effect，服务应按名称/标签兼容识别为 promo。
        rows.add(image(1L, "淡斑优惠活动海报", "effect", true, now.minusDays(1), now.plusDays(1)));
        rows.add(image(2L, "效果图", "effect", true, null, null));
        rows.add(image(3L, "过期优惠海报", "effect", true, now.minusDays(3), now.minusDays(1)));
        rows.add(image(4L, "停用优惠海报", "effect", false, null, null));
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString())).thenReturn(rows);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), any(Object[].class))).thenReturn(rows);

        List<Map<String, Object>> result = service.matchInitialProjectAssets("淡斑");

        assertEquals(2, result.size());
        assertEquals("效果图", result.get(0).get("name"));
        assertEquals("淡斑优惠活动海报", result.get(1).get("name"));
    }

    @Test
    void 后续主动索图_排除已发送图片且每次只返回一张() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ImageService service = new ImageService(jdbc, tempDir.toString());
        List<Map<String, Object>> rows = List.of(
                image(11L, "已经发过的效果图", "effect", true, null, null),
                image(12L, "新的效果图1", "effect", true, null, null),
                image(13L, "新的效果图2", "effect", true, null, null));
        when(jdbc.queryForList(contains("FROM image_asset"))).thenReturn(rows);
        when(jdbc.queryForList(contains("FROM image_asset"), any(Object[].class))).thenReturn(rows);
        when(jdbc.queryForList(contains("conversation_image_send"), eq(Long.class), eq("conv-1")))
                .thenReturn(List.of(11L));

        List<Map<String, Object>> result = service.matchByRequest("再发一张淡斑效果图", "淡斑", "conv-1");

        assertEquals(1, result.size());
        assertEquals(12L, result.get(0).get("id"));
    }

    @Test
    void 外部渠道发送_读取当前素材原始字节且不暴露路径() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ImageService service = new ImageService(jdbc, tempDir.toString());
        Path image = tempDir.resolve("images").resolve("asset-7.jpg");
        byte[] expected = new byte[]{1, 2, 3, 4};
        Files.write(image, expected);
        when(jdbc.queryForList(contains("SELECT file_path"), eq(7L)))
                .thenReturn(List.of(Map.of("file_path", image.toString())));
        when(jdbc.queryForList(contains("SELECT i.file_path"), any(Object[].class)))
                .thenReturn(List.of(Map.of("file_path", image.toString())));

        ImageService.OutboundImage outbound = service.loadForExternalSend(7L);

        assertEquals("asset-7.jpg", outbound.filename());
        assertEquals("image/jpeg", outbound.contentType());
        assertArrayEquals(expected, outbound.bytes());
    }

    private Map<String, Object> image(long id, String name, String type, boolean enabled,
                                      LocalDateTime from, LocalDateTime until) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("url", "/" + name + ".jpg");
        row.put("tags", "淡斑");
        row.put("project", "淡斑");
        row.put("asset_type", type);
        row.put("enabled", enabled);
        row.put("valid_from", from == null ? null : Timestamp.valueOf(from));
        row.put("valid_until", until == null ? null : Timestamp.valueOf(until));
        return row;
    }
}

package com.example.aics.report;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 管理后台 CSV 导出工具：UTF-8 带 BOM（Excel 直开不乱码）、
 * 逗号/引号/换行按 RFC 4180 转义、以附件形式下载。
 */
public final class CsvExport {

    private CsvExport() {}

    /** @param filename ASCII 文件名（不含中文），header 与每行 cells 数量需一致 */
    public static ResponseEntity<byte[]> response(String filename,
                                                  List<String> header,
                                                  List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(line(header));
        for (List<Object> row : rows) {
            sb.append(line(row));
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .contentLength(body.length)
                .body(body);
    }

    private static CharSequence line(List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        return sb.append('\r').append('\n');
    }

    private static String cell(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}

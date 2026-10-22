package com.example.aics.knowledge;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文档解析：把上传的文件抽成 Markdown 格式文本（统一入库格式）
 * 支持：pdf / docx / xlsx / txt / md / 图片（jpg/png 等，走 qwen-vl-max 视觉识别）
 *
 * 2026-08-07 改为"统一转 md"：
 * - docx：段落样式（Heading1/2/…）→ # 标题，表格 → Markdown 表格
 * - xlsx：sheet 名 → ## 标题，每行 → | a | b | c | 表格行
 * - pdf：抽取纯文本并压缩多余空行（PDF 无结构，保持段落即可）
 * - txt / md：原样（txt 无结构，md 本身已是 Markdown）
 * 这样知识库所有文档以统一 md 风格维护，管理后台编辑时可读性最好。
 */
@Component
public class DocumentTextExtractor {

    private final OcrService ocrService;

    public DocumentTextExtractor(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public String extract(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();

        return switch (ext) {
            case "pdf" -> extractPdf(file);
            case "docx" -> extractDocx(file);
            case "xlsx" -> extractXlsx(file);
            case "txt", "md" -> new String(file.getBytes(), StandardCharsets.UTF_8);
            case "jpg", "jpeg" -> ocrService.extractText(file.getBytes(), "image/jpeg");
            case "png" -> ocrService.extractText(file.getBytes(), "image/png");
            case "bmp" -> ocrService.extractText(file.getBytes(), "image/bmp");
            case "webp" -> ocrService.extractText(file.getBytes(), "image/webp");
            default -> throw new IllegalArgumentException(
                    "暂不支持的文件类型: " + ext + "（目前支持 pdf / docx / xlsx / txt / md / jpg / png / bmp / webp）");
        };
    }

    private String extractPdf(MultipartFile file) throws IOException {
        // PDFBox 3.x 改了 API：PDDocument.load() 已移除，统一用 Loader.loadPDF()。
        // 注意：上传流必须与 PDDocument 一起纳入资源管理，否则容器 part 流/临时文件句柄泄漏。
        try (var in = file.getInputStream();
             PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
            String text = new PDFTextStripper().getText(doc);
            // PDF 无结构信息：压缩多余空行（3 个以上空行合成 1 个），其余保持段落文本
            return text.replaceAll("\\n{3,}", "\n\n").trim();
        }
    }

    /**
     * Word(docx) → Markdown：
     * 段落按 Word 样式转标题（Heading1 → #，Heading2 → ## …），正文转普通文本；
     * 表格转 Markdown 表格（| a | b | c |）。
     */
    private String extractDocx(MultipartFile file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
            for (org.apache.poi.xwpf.usermodel.IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String text = p.getText() == null ? "" : p.getText().trim();
                    if (text.isEmpty()) {
                        sb.append("\n");
                        continue;
                    }
                    int level = headingLevelOf(p.getStyle());
                    if (level > 0) {
                        sb.append("#".repeat(level)).append(" ").append(text).append("\n\n");
                    } else {
                        sb.append(text).append("\n\n");
                    }
                } else if (el instanceof org.apache.poi.xwpf.usermodel.XWPFTable t) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : t.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (org.apache.poi.xwpf.usermodel.XWPFTableCell c : row.getTableCells()) {
                            String v = (c.getText() == null ? "" : c.getText()).trim()
                                    .replace("|", "\\|").replaceAll("\\n+", " ");
                            if (!v.isEmpty()) cells.add(v);
                        }
                        if (!cells.isEmpty()) {
                            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
                        }
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * Excel(xlsx) → Markdown：每个 sheet 转一个 ## 小标题。
     * 按表宽自动选择格式（2026-08-07 优化，解决 price.xlsx 长文本表格混乱问题）：
     * - 宽表（列数 > 4）或含长文本（任一单元格 > 60 字符）→ 键值对格式：
     *   每行数据一块（### 首列作标题），字段名：值 逐行列出，长话术不会被表格跨行撑爆；
     * - 窄表 → Markdown 表格（| a | b |），单元格里的 | 转义为 \|。
     */
    private String extractXlsx(MultipartFile file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sb.append("## ").append(sheet.getSheetName()).append("\n");

                // 先把整个 sheet 读成行数组（跳过空行）
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String value = cellToString(cell).trim();
                        if (!value.isEmpty()) {
                            cells.add(value.replace("|", "\\|"));
                        }
                    }
                    if (!cells.isEmpty()) {
                        rows.add(cells);
                    }
                }
                if (rows.isEmpty()) continue;

                List<String> header = rows.get(0);
                boolean wide = header.size() > 4
                        || rows.stream().anyMatch(r -> r.stream().anyMatch(c -> c.length() > 60));

                if (wide) {
                    // 键值对格式：每行数据一块
                    for (int i = 1; i < rows.size(); i++) {
                        List<String> row = rows.get(i);
                        if (row.isEmpty()) continue;
                        // 首列（如项目名）作三级标题；空则用"记录 i"
                        String title = row.get(0).replace("\\|", "|");
                        sb.append("### ").append(title.isBlank() ? "记录 " + i : title).append("\n");
                        for (int j = 0; j < header.size() && j < row.size(); j++) {
                            String key = header.get(j);
                            String val = row.get(j);
                            if (key.isBlank() || val.isBlank()) continue;
                            sb.append("- ").append(key).append("：").append(val).append("\n");
                        }
                        sb.append("\n");
                    }
                } else {
                    // 窄表：保持 Markdown 表格
                    for (List<String> row : rows) {
                        sb.append("| ").append(String.join(" | ", row)).append(" |\n");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /** 从 Word 段落样式 ID 解析标题级别：Heading1/标题 1 → 1，Heading2 → 2 …；普通段落返回 0 */
    private int headingLevelOf(String style) {
        if (style == null || style.isBlank()) return 0;
        String s = style.toLowerCase();
        int idx = s.indexOf("heading");
        if (idx < 0) return 0;
        String num = s.substring(idx + "heading".length()).replaceAll("[^0-9]", "");
        if (num.isEmpty()) return 1;   // "Heading" 无数字视为一级标题
        try {
            int level = Integer.parseInt(num);
            return Math.min(level, 6);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 识别"标准 FAQ 表"（首行包含「问题」「答案」两列，可带「分类」列的 xlsx）。
     * 是 FAQ 表 → 返回逐行的 [问题, 答案, 分类] 列表；不是 → 返回 null，走普通文本流程。
     * FAQ 表一行一片单独入库，检索更精准，还能支持"高置信命中直接回复标准答案"。
     * 2026-08-07：第三列「分类」也入库（如"抗衰/禁忌症"），管理后台可分类查看。
     */
    public List<String[]> tryExtractFaqRows(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase().endsWith(".xlsx")) return null;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) return null;

            int qCol = -1, aCol = -1, cCol = -1;
            for (Cell cell : header) {
                String v = cellToString(cell).trim();
                if (v.equals("问题")) qCol = cell.getColumnIndex();
                if (v.equals("答案")) aCol = cell.getColumnIndex();
                if (v.equals("分类") || v.equals("分类名称") || v.equals("类别")) cCol = cell.getColumnIndex();
            }
            if (qCol < 0 || aCol < 0) return null;

            List<String[]> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String q = cellToString(row.getCell(qCol)).trim();
                String a = cellToString(row.getCell(aCol)).trim();
                String c = cCol >= 0 ? cellToString(row.getCell(cCol)).trim() : "";
                if (!q.isEmpty() && !a.isEmpty()) {
                    rows.add(new String[]{q, a, c});
                }
            }
            return rows.isEmpty() ? null : rows;
        }
    }

    /** price.xlsx 每行的标准化结果；content 用于知识库留档，searchText 仅用于价格检索。 */
    public record PriceRow(String projectKey, String content, String searchText) {}

    /**
     * 解析价格唯一来源 price.xlsx。必须包含 project_key、activity_price；
     * original_price、deposit、brief_copy 可选。解析失败会拒绝整份上传，不覆盖旧价格表。
     */
    public List<PriceRow> tryExtractPriceRows(MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String simpleName = original.replace('\\', '/');
        simpleName = simpleName.substring(simpleName.lastIndexOf('/') + 1);
        if (!"price.xlsx".equalsIgnoreCase(simpleName)) return null;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("price.xlsx 没有工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) throw new IllegalArgumentException("price.xlsx 缺少表头");

            int projectCol = findColumn(header, "projectkey", "项目key", "项目键");
            int activityCol = findColumn(header, "activityprice", "活动价", "价格");
            int originalCol = findColumn(header, "originalprice", "原价");
            int depositCol = findColumn(header, "deposit", "订金", "定金");
            int briefCol = findColumn(header, "briefcopy", "简短话术", "回复话术");
            if (projectCol < 0 || activityCol < 0) {
                throw new IllegalArgumentException("price.xlsx 必须包含 project_key 和 activity_price 两列");
            }

            List<PriceRow> result = new ArrayList<>();
            Set<String> seenProjects = new HashSet<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String projectKey = cellToString(row.getCell(projectCol)).trim();
                String activityPrice = cellToString(row.getCell(activityCol)).trim();
                if (projectKey.isBlank() && activityPrice.isBlank()) continue;
                if (projectKey.isBlank()) throw new IllegalArgumentException("price.xlsx 第 " + (i + 1) + " 行缺少 project_key");
                java.math.BigDecimal activityAmount = validateMoney(activityPrice, "activity_price", i + 1, true);
                if (!seenProjects.add(projectKey)) {
                    throw new IllegalArgumentException("price.xlsx 中 project_key 重复：" + projectKey);
                }

                String originalPrice = valueAt(row, originalCol);
                String deposit = valueAt(row, depositCol);
                String briefCopy = valueAt(row, briefCol);
                validateMoney(originalPrice, "original_price", i + 1, false);
                java.math.BigDecimal depositAmount = validateMoney(deposit, "deposit", i + 1, false);
                if (depositAmount != null && depositAmount.compareTo(activityAmount) > 0) {
                    throw new IllegalArgumentException("price.xlsx 第 " + (i + 1) + " 行 deposit 不能大于 activity_price");
                }

                StringBuilder content = new StringBuilder("### ").append(projectKey).append('\n')
                        .append("- project_key：").append(projectKey).append('\n')
                        .append("- activity_price：").append(activityPrice).append('\n');
                if (!originalPrice.isBlank()) content.append("- original_price：").append(originalPrice).append('\n');
                if (!deposit.isBlank()) content.append("- deposit：").append(deposit).append('\n');
                if (!briefCopy.isBlank()) content.append("- brief_copy：").append(briefCopy).append('\n');

                // 保留价格表中的其他字段（例如 sale_copy）供后台编辑和审计，但价格回答不会直接使用它们。
                for (Cell headerCell : header) {
                    String field = cellToString(headerCell).trim();
                    String normalized = normalizeHeader(field);
                    if (field.isBlank() || isCanonicalPriceField(normalized)) continue;
                    String value = cellToString(row.getCell(headerCell.getColumnIndex())).trim();
                    if (!value.isBlank()) content.append("- ").append(field).append("：").append(value).append('\n');
                }

                String searchText = "项目：" + projectKey + "\n价格 收费 费用 多少钱 活动价 原价 订金 定金"
                        + (briefCopy.isBlank() ? "" : "\n" + briefCopy);
                result.add(new PriceRow(projectKey, content.toString().trim(), searchText));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("price.xlsx 没有有效价格行");
            return result;
        }
    }

    private int findColumn(Row header, String... aliases) {
        for (Cell cell : header) {
            String normalized = normalizeHeader(cellToString(cell));
            for (String alias : aliases) {
                if (normalized.equals(normalizeHeader(alias))) return cell.getColumnIndex();
            }
        }
        return -1;
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-]", "");
    }

    private boolean isCanonicalPriceField(String normalized) {
        return Set.of("projectkey", "项目key", "项目键", "activityprice", "活动价", "价格",
                "originalprice", "原价", "deposit", "订金", "定金", "briefcopy", "简短话术", "回复话术")
                .contains(normalized);
    }

    private String valueAt(Row row, int column) {
        return column < 0 ? "" : cellToString(row.getCell(column)).trim();
    }

    private java.math.BigDecimal validateMoney(String value, String field, int rowNumber, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException("price.xlsx 第 " + rowNumber + " 行缺少 " + field);
            return null;
        }
        String number = value.replace(",", "").replaceAll("[^0-9.\\-]", "");
        try {
            java.math.BigDecimal parsed = new java.math.BigDecimal(number);
            if (number.isBlank() || parsed.signum() < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("price.xlsx 第 " + rowNumber + " 行 " + field + " 不是有效金额：" + value);
        }
    }

    /** 把各种类型的单元格统一转成字符串 */
    private String cellToString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> "";
        };
    }
}

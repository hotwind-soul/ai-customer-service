package com.example.aics.knowledge;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DocumentTextExtractorPriceTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor(mock(OcrService.class));

    @Test
    void price表逐行标准化且检索文本不包含长营销话术() throws Exception {
        MockMultipartFile file = workbook(
                new String[][]{
                        {"project_key", "activity_price", "original_price", "deposit", "sale_copy", "brief_copy"},
                        {"淡斑", "198", "1980", "8", "这是一段很长的营销话术", "淡斑活动价198元，8元订金可抵扣，到店再付190元。"},
                        {"眼袋", "468", "2980", "9", "另一段营销话术", "眼袋活动价468元，9元订金可抵扣，到店再付459元。"}
                });

        List<DocumentTextExtractor.PriceRow> rows = extractor.tryExtractPriceRows(file);

        assertEquals(2, rows.size());
        assertEquals("淡斑", rows.get(0).projectKey());
        assertTrue(rows.get(0).content().contains("- activity_price：198"));
        assertTrue(rows.get(0).content().contains("- sale_copy：这是一段很长的营销话术"));
        assertTrue(rows.get(0).searchText().contains("淡斑"));
        assertFalse(rows.get(0).searchText().contains("很长的营销话术"));
    }

    @Test
    void deposit大于活动价时拒绝整份上传() throws Exception {
        MockMultipartFile file = workbook(new String[][]{
                {"project_key", "activity_price", "deposit"},
                {"淡斑", "198", "298"}
        });

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> extractor.tryExtractPriceRows(file));

        assertTrue(error.getMessage().contains("deposit 不能大于 activity_price"));
    }

    private MockMultipartFile workbook(String[][] values) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("价格表");
            for (int r = 0; r < values.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < values[r].length; c++) row.createCell(c).setCellValue(values[r][c]);
            }
            workbook.write(out);
            return new MockMultipartFile("file", "price.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }
}

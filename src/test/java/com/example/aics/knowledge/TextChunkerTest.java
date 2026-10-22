package com.example.aics.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本切片单元测试（W25-S4）：切片边界/重叠是 RAG 检索质量的基础，
 * 改坏会导致一句话被切断、检索上下文不完整。
 */
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    void blankReturnsEmpty() {
        assertTrue(chunker.chunk("").isEmpty());
        assertTrue(chunker.chunk("   ").isEmpty());
        assertTrue(chunker.chunk(null).isEmpty());
    }

    @Test
    void shortTextSingleChunk() {
        List<String> chunks = chunker.chunk("今天天气不错，适合出门。");
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("今天天气不错"));
    }

    @Test
    void longParagraphHardSplitWithOverlap() {
        // 700 字长段落：应切成多片，且相邻片有重叠（防止一句话被切断）
        String para = "抗".repeat(700);
        List<String> chunks = chunker.chunk(para);
        assertTrue(chunks.size() >= 2, "700 字应切成至少 2 片");
        // 每片不超过 400 字
        for (String c : chunks) {
            assertTrue(c.length() <= 400, "单片不能超过 CHUNK_SIZE");
        }
        // 相邻片有重叠：前一片尾部能在后一片头部找到
        for (int i = 1; i < chunks.size(); i++) {
            String prevTail = chunks.get(i - 1).length() > 50
                    ? chunks.get(i - 1).substring(chunks.get(i - 1).length() - 50)
                    : chunks.get(i - 1);
            assertTrue(chunks.get(i).startsWith(prevTail.substring(0, Math.min(10, prevTail.length()))),
                    "相邻片应带重叠衔接");
        }
    }

    @Test
    void paragraphsMergedUntilSizeLimit() {
        // 两个小段应合并进同一片
        String text = "第一段内容。\n第二段内容。";
        List<String> chunks = chunker.chunk(text);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段"));
        assertTrue(chunks.get(0).contains("第二段"));
    }
}

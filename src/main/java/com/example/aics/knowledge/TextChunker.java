package com.example.aics.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切片：把长文档切成 300~500 字的小段，段与段之间保留 50 字重叠。
 *
 * 为什么要重叠：防止一句话刚好被从中间切断，导致检索时上下文不完整。
 * 切多大、叠多少是第 5 周调优的重点参数。
 */
@Component
public class TextChunker {

    private static final int CHUNK_SIZE = 400;   // 每片目标长度（字）
    private static final int OVERLAP = 50;       // 相邻片重叠长度（字）

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        // 统一换行符，压缩多余空白
        String clean = text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").trim();

        // 优先按段落（换行）切，段落太长再硬切
        String[] paragraphs = clean.split("\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            // 单段就比目标长度还长：先把 current 收片，再把长段硬切
            if (para.length() > CHUNK_SIZE) {
                flush(chunks, current);
                hardSplit(chunks, para);
                continue;
            }
            if (current.length() + para.length() > CHUNK_SIZE) {
                flush(chunks, current);
                // 新片从上一片尾部带回 OVERLAP 个字，保证衔接
                current.append(tailOf(chunks.get(chunks.size() - 1)));
            }
            current.append(para).append("\n");
        }
        flush(chunks, current);
        return chunks;
    }

    private void hardSplit(List<String> chunks, String para) {
        int start = 0;
        while (start < para.length()) {
            int end = Math.min(start + CHUNK_SIZE, para.length());
            chunks.add(para.substring(start, end));
            start = end - OVERLAP;          // 回退一点形成重叠
            if (start >= para.length() - OVERLAP) break;
        }
    }

    private void flush(List<String> chunks, StringBuilder current) {
        String s = current.toString().trim();
        if (!s.isEmpty()) {
            chunks.add(s);
        }
        current.setLength(0);
    }

    private String tailOf(String lastChunk) {
        return lastChunk.length() <= OVERLAP
                ? lastChunk
                : lastChunk.substring(lastChunk.length() - OVERLAP);
    }
}

package com.example.aics.knowledge;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 内存版 MultipartFile：异步知识导入时，把 request 里的文件先转成 byte[]，
 * 再在后台线程里包装成 MultipartFile 复用现有解析逻辑（DocumentTextExtractor 全走
 * getBytes/getInputStream/getOriginalFilename，不依赖磁盘临时文件）。
 * 文件大小上限受 application.yml 的 max-file-size（50MB）约束，内存可接受。
 */
public class BytesMultipartFile implements MultipartFile {

    private final String originalFilename;
    private final byte[] content;
    private final String contentType;

    public BytesMultipartFile(String originalFilename, byte[] content) {
        this.originalFilename = originalFilename == null ? "未命名文档" : originalFilename;
        this.content = content == null ? new byte[0] : content;
        this.contentType = guessContentType(this.originalFilename);
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        // 异步导入走内存，不做磁盘转移
        throw new IOException("BytesMultipartFile 不支持 transferTo");
    }

    /** 按扩展名粗略推断 Content-Type（OCR 分支需要 image/*） */
    private static String guessContentType(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/octet-stream";
        };
    }
}

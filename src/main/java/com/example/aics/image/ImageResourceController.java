package com.example.aics.image;

import com.example.aics.config.CustomerIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 图片文件统一从受控端点读取。运营素材可长期公开展示；c_ 开头的客户上传图只允许其本人查看。
 */
@RestController
public class ImageResourceController {

    private final ImageService images;
    private final CustomerIdentityService identities;

    public ImageResourceController(ImageService images, CustomerIdentityService identities) {
        this.images = images;
        this.identities = identities;
    }

    @GetMapping("/uploads/images/{filename:.+}")
    public ResponseEntity<Resource> image(@PathVariable String filename,
                                          HttpServletRequest request,
                                          HttpServletResponse response) throws IOException {
        String userId = filename.startsWith("c_") ? identities.resolve(request, response) : "";
        Resource resource = images.loadWebImage(filename, userId);
        if (resource == null) return ResponseEntity.notFound().build();
        MediaType mediaType = mediaType(filename);
        CacheControl cache = filename.startsWith("c_")
                ? CacheControl.noStore()
                : CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable();
        return ResponseEntity.ok().contentType(mediaType).cacheControl(cache).body(resource);
    }

    private MediaType mediaType(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".bmp")) return MediaType.parseMediaType("image/bmp");
        return MediaType.IMAGE_JPEG;
    }
}

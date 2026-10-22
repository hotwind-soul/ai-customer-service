package com.example.aics.image;

import com.example.aics.audit.AuditService;
import com.example.aics.config.AdminResourceScopeService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 图片素材管理接口
 * 上传：POST /images/upload （表单：file 图片文件、name 图片名、tags 标签空格分隔）
 * 列表：GET  /images/list
 * 删除：DELETE /images?id=1
 *
 * 上传示例（cmd）：
 * curl -F "file=@C:\图片\眼袋对比.jpg" -F "name=眼袋前后对比" -F "tags=眼袋 对比图 案例" http://localhost:8080/images/upload
 */
@RestController
@RequestMapping("/images")
public class ImageController {

    private final ImageService imageService;
    private final AuditService auditService;
    private final AdminResourceScopeService resourceScopes;

    public ImageController(ImageService imageService, AuditService auditService,
                           AdminResourceScopeService resourceScopes) {
        this.imageService = imageService;
        this.auditService = auditService;
        this.resourceScopes = resourceScopes;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String name,
                                      @RequestParam(required = false) String tags,
                                      @RequestParam(required = false) String project,
                                      @RequestParam(required = false) String assetType,
                                      @RequestParam(required = false) Boolean enabled,
                                      @RequestParam(required = false) String validFrom,
                                      @RequestParam(required = false) String validUntil,
                                      @RequestParam(required = false) Long tenantId,
                                      @RequestParam(required = false) Long kfAccountId) throws IOException {
        return resourceScopes.executeIo(tenantId, kfAccountId, () -> {
            Map<String, Object> saved = imageService.save(
                    file, name, tags, project, assetType, enabled, validFrom, validUntil);
            auditService.log("UPLOAD_IMAGE", String.valueOf(saved.get("name")), "admin");
            return saved;
        });
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        return imageService.list();
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Map<String, Object> result = Map.of("deleted", imageService.delete(id));
        auditService.log("DELETE_IMAGE", "图片ID=" + id, "admin");
        return result;
    }

    /** 更新图片名称/标签（管理端编辑用），body 形如 {"name":"眼袋前后对比","tags":"眼袋 对比图"} */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        String name = body == null ? null : stringValue(body.get("name"));
        String tags = body == null ? null : stringValue(body.get("tags"));
        String project = body == null ? null : stringValue(body.get("project"));
        String assetType = body == null ? null : stringValue(body.get("assetType"));
        Boolean enabled = body == null || body.get("enabled") == null
                ? null : Boolean.valueOf(String.valueOf(body.get("enabled")));
        String validFrom = body == null ? null : stringValue(body.get("validFrom"));
        String validUntil = body == null ? null : stringValue(body.get("validUntil"));
        Map<String, Object> result = Map.of("updated", imageService.update(
                id, stringValue(name), tags, project, assetType, enabled, validFrom, validUntil));
        auditService.log("UPDATE_IMAGE", "图片ID=" + id + " 名称=" + name, "admin");
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 上传参数/格式不合法（如扩展名不在白名单）→ 400，而不是 500 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> onBadRequest(IllegalArgumentException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}

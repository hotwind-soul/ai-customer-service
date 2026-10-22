package com.example.aics.ai;

import com.example.aics.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 超级管理员模型管理接口；鉴权由 AdminAuthInterceptor 统一执行。 */
@RestController
@RequestMapping("/api/ai-models")
public class AiModelProviderController {
    private final AiModelProviderService providers;
    private final ModelGateway gateway;
    private final AuditService audit;

    public AiModelProviderController(AiModelProviderService providers, ModelGateway gateway,
                                     AuditService audit) {
        this.providers = providers;
        this.gateway = gateway;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = providers.list();
        return Map.of("models", rows,
                "primary", rows.stream().filter(row -> "primary".equals(row.get("role"))).findFirst().orElse(Map.of()),
                "fallback", rows.stream().filter(row -> "fallback".equals(row.get("role"))).findFirst().orElse(Map.of()));
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody SaveRequest body, HttpServletRequest request) {
        long id = providers.create(body.displayName(), body.baseUrl(), body.modelName(), body.apiKey(),
                body.enabled() == null || body.enabled(), body.role());
        audit.log("CREATE_AI_MODEL", "model_id=" + id, actor(request));
        return Map.of("id", id, "result", "模型已添加并立即生效");
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody SaveRequest body,
                                      HttpServletRequest request) {
        providers.update(id, body.displayName(), body.baseUrl(), body.modelName(), body.apiKey());
        audit.log("UPDATE_AI_MODEL", "model_id=" + id, actor(request));
        return Map.of("result", "模型配置已保存并立即生效");
    }

    @PostMapping("/{id}/role")
    public Map<String, Object> role(@PathVariable long id, @RequestBody RoleRequest body,
                                    HttpServletRequest request) {
        providers.assignRole(id, body.role());
        audit.log("ASSIGN_AI_MODEL_ROLE", "model_id=" + id + " role=" + body.role(), actor(request));
        return Map.of("result", "主备模型已切换并立即生效");
    }

    @PostMapping("/{id}/enabled")
    public Map<String, Object> enabled(@PathVariable long id, @RequestBody EnabledRequest body,
                                       HttpServletRequest request) {
        providers.setEnabled(id, Boolean.TRUE.equals(body.enabled()));
        audit.log(Boolean.TRUE.equals(body.enabled()) ? "ENABLE_AI_MODEL" : "DISABLE_AI_MODEL",
                "model_id=" + id, actor(request));
        return Map.of("result", Boolean.TRUE.equals(body.enabled()) ? "模型已启用" : "模型已停用");
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable long id) {
        return gateway.testProvider(id);
    }

    /** 仅超级管理员：查看该模型已保存的 API Key 明文（每次查看都写审计日志）。 */
    @GetMapping("/{id}/reveal-key")
    public Map<String, Object> revealKey(@PathVariable long id, HttpServletRequest request) {
        if (!"super_admin".equals(String.valueOf(request.getAttribute("aics.admin.role")))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "仅超级管理员可以查看模型密钥");
        }
        String value = providers.revealApiKey(id);
        audit.log("REVEAL_AI_MODEL_KEY", "model_id=" + id, actor(request));
        return Map.of("id", id, "value", value);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
        providers.delete(id);
        audit.log("DELETE_AI_MODEL", "model_id=" + id, actor(request));
        return Map.of("result", "模型已删除");
    }

    private static String actor(HttpServletRequest request) {
        Object value = request.getAttribute("aics.admin.actor");
        return value == null ? "super_admin" : String.valueOf(value);
    }

    public record SaveRequest(String displayName, String baseUrl, String modelName, String apiKey,
                              Boolean enabled, String role) {}
    public record RoleRequest(String role) {}
    public record EnabledRequest(Boolean enabled) {}
}

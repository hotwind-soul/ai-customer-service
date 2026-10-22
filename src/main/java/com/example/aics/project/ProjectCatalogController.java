package com.example.aics.project;

import com.example.aics.audit.AuditService;
import com.example.aics.config.AdminResourceScopeService;
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

/** 后台项目中心：一次发布项目、价格、别名、图片和支持门店。 */
@RestController
@RequestMapping("/projects")
public class ProjectCatalogController {

    private final ProjectCatalogService projects;
    private final AdminResourceScopeService resourceScopes;
    private final AuditService auditService;

    public ProjectCatalogController(ProjectCatalogService projects,
                                    AdminResourceScopeService resourceScopes,
                                    AuditService auditService) {
        this.projects = projects;
        this.resourceScopes = resourceScopes;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return projects.list();
    }

    /** 图片/门店编辑器使用的轻量项目下拉选项。 */
    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of("projects", projects.adminPublishedNames());
    }

    /** 门店编辑使用：只返回一级主项目，子项目默认跟随主项目。 */
    @GetMapping("/root-options")
    public Map<String, Object> rootOptions() {
        return Map.of("projects", projects.adminRootPublishedNames());
    }

    /** 项目编辑器关联素材和门店，不要求前端跨模块调用其他管理接口。 */
    @GetMapping("/resources")
    public Map<String, Object> resources() {
        return projects.resourceOptions();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ProjectRequest request) {
        long id = resourceScopes.execute(request.tenantId(), request.kfAccountId(),
                () -> projects.create(request.command()));
        auditService.log("CREATE_SERVICE_PROJECT", request.name() + " ID=" + id, "admin");
        return Map.of("id", id, "result", "项目已创建并同步到全部使用入口");
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody ProjectRequest request) {
        projects.update(id, request.command());
        auditService.log("UPDATE_SERVICE_PROJECT", request.name() + " ID=" + id, "admin");
        return Map.of("updated", 1, "result", "项目已发布并同步到全部使用入口");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> disable(@PathVariable long id) {
        projects.disable(id);
        auditService.log("DISABLE_SERVICE_PROJECT", "项目ID=" + id, "admin");
        return Map.of("updated", 1, "result", "项目已停用，历史数据仍保留");
    }

    public record ProjectRequest(Long tenantId, Long kfAccountId,
                                 Long parentId, String code, String name, String serviceScope,
                                 String status, Integer sortOrder,
                                 List<String> aliases, ProjectCatalogService.OfferCommand offer,
                                 List<Long> imageIds, List<Long> storeIds) {
        ProjectCatalogService.ProjectCommand command() {
            return new ProjectCatalogService.ProjectCommand(
                    parentId, code, name, serviceScope, status, sortOrder,
                    aliases, offer, imageIds, storeIds);
        }
    }
}

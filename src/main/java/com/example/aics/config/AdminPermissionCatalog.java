package com.example.aics.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可分配给普通管理员的后台权限目录。
 *
 * 接口设置和管理员账号管理故意不出现在目录中：这两项能力固定只属于超级管理员，
 * 无法通过数据库权限记录下放，避免误配置造成越权。
 */
public final class AdminPermissionCatalog {

    private AdminPermissionCatalog() {
    }

    public record Permission(String code, String label, String description) {
    }

    private static final List<Permission> DEFINITIONS = List.of(
            new Permission("report.view", "AI 概览", "查看 AI 应答、转人工和热点问题报表"),
            new Permission("quality.manage", "质量评分与复核", "在会话与质检页面评分会话、人工复核并维护回归用例"),
            new Permission("ai.debug", "AI 调优与策略", "诊断对话、维护回归用例，并管理业务话术、阈值和工作时间"),
            new Permission("conversations.view", "真实会话查看", "在会话与质检页面查询会话并查看消息和决策追踪"),
            new Permission("conversations.delete", "会话删除", "物理删除单条会话及其全部消息，不可恢复"),
            new Permission("knowledge.manage", "知识库", "上传、编辑、发布和删除知识内容"),
            new Permission("images.manage", "图片素材", "上传、编辑和删除 AI 图片素材"),
            new Permission("stores.manage", "门店资料", "维护门店、地址、项目和定位信息"),
            new Permission("audit.view", "操作日志", "查看后台关键操作审计记录"),
            new Permission("agents.manage", "客服账号", "创建客服坐席并重置坐席密码"),
            new Permission("operations.manage", "客户运营", "处理预约和唤醒任务")
    );

    public static List<Permission> definitions() {
        return DEFINITIONS;
    }

    public static List<String> codes() {
        return DEFINITIONS.stream().map(Permission::code).toList();
    }

    public static boolean isKnown(String code) {
        return DEFINITIONS.stream().anyMatch(permission -> permission.code().equals(code));
    }

    public static List<Map<String, String>> asMaps() {
        return DEFINITIONS.stream().map(permission -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", permission.code());
            item.put("label", permission.label());
            item.put("description", permission.description());
            return item;
        }).toList();
    }
}

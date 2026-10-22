package com.example.aics.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 返回当前管理员可用于新建知识、图片和门店的企业/客服号范围。 */
@RestController
@RequestMapping("/api/resource-scopes")
public class AdminResourceScopeController {

    private final AdminResourceScopeService scopes;

    public AdminResourceScopeController(AdminResourceScopeService scopes) {
        this.scopes = scopes;
    }

    @GetMapping
    public Map<String, Object> catalog() {
        return scopes.catalog();
    }
}

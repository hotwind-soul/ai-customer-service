package com.example.aics.store;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 客户端“查询附近门店”快捷面板用：公开只读门店列表，无需后台口令。
 * 路径 /api/public-stores 不在 AdminAuthInterceptor 拦截列表内，对访客开放。
 * 仅暴露门店基础信息（城市/区县/名称/地址等），不含任何管理操作。
 */
@RestController
@RequestMapping("/api")
public class StorePublicController {

    private final StoreService storeService;

    public StorePublicController(StoreService storeService) {
        this.storeService = storeService;
    }

    @GetMapping("/public-stores")
    public List<Map<String, Object>> publicStores(
            @RequestParam(required = false) String city) {
        return storeService.list(city, null);
    }
}

package com.example.aics.store;

import com.example.aics.audit.AuditService;
import com.example.aics.config.AdminResourceScopeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 门店管理接口（后台 CRUD + 就近查询）
 * GET    /stores?city=昆明     列表（可按城市过滤）
 * POST   /stores               新增（JSON body）
 * PUT    /stores/{id}          编辑
 * DELETE /stores/{id}          删除
 * GET    /stores/nearby?lat=..&lng=..  按坐标找最近门店
 * GET    /stores/search?location=昆明官渡  按城市/区县名找门店
 */
@RestController
@RequestMapping("/stores")
public class StoreController {

    private final StoreService storeService;
    private final AuditService auditService;
    private final MapService mapService;
    private final AdminResourceScopeService resourceScopes;

    public StoreController(StoreService storeService, AuditService auditService, MapService mapService,
                           AdminResourceScopeService resourceScopes) {
        this.storeService = storeService;
        this.auditService = auditService;
        this.mapService = mapService;
        this.resourceScopes = resourceScopes;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String city,
                                          @RequestParam(required = false) String address) {
        return storeService.list(city, address);
    }

    /** 门店地图选点用：返回腾讯地图 JS SDK key（受 stores.manage 管理鉴权保护）。 */
    @GetMapping("/map-key")
    public Map<String, Object> mapKey() {
        String key = mapService.jsKey();
        return Map.of("configured", !key.isBlank(), "key", key);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> store) {
        Map<String, Object> result = resourceScopes.execute(longValue(store.get("tenantId")),
                longValue(store.get("kfAccountId")), () -> Map.of("id", storeService.create(store)));
        auditService.log("CREATE_STORE", String.valueOf(store.get("name")), "admin");
        return result;
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> store) {
        Map<String, Object> result = Map.of("updated", storeService.update(id, store));
        auditService.log("UPDATE_STORE", "门店ID=" + id + " " + store.get("name"), "admin");
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        Map<String, Object> result = Map.of("deleted", storeService.delete(id));
        auditService.log("DELETE_STORE", "门店ID=" + id, "admin");
        return result;
    }

    @GetMapping("/nearby")
    public List<Map<String, Object>> nearby(@RequestParam double lat,
                                            @RequestParam double lng,
                                            @RequestParam(defaultValue = "3") int limit) {
        return storeService.findNearest(lat, lng, limit);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String location) {
        return storeService.findByLocation(location);
    }

    /**
     * 地址 → 经纬度（腾讯地图地理编码，前端"输入地址自动定位"用）。
     * POST /stores/geocode {"address":"云南省昆明市官渡区星耀路99号","city":"昆明"}
     * 返回 {"ok":true,"lat":24.98..,"lng":102.71..}；解析失败 {"ok":false,"error":"..."}
     */
    @PostMapping("/geocode")
    public Map<String, Object> geocode(@RequestBody Map<String, String> body) {
        double[] coord = mapService.geocode(body.get("address"), body.get("city"));
        if (coord == null) {
            return Map.of("ok", false, "error", "地址无法解析（未配置地图 key 或地址不标准）");
        }
        return Map.of("ok", true, "lat", coord[0], "lng", coord[1]);
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("资源归属 ID 格式不正确"); }
    }
}

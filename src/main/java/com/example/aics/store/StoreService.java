package com.example.aics.store;

import com.example.aics.channel.TenantExecutionContext;
import com.example.aics.config.ScopedDataAccess;
import com.example.aics.message.LinkCard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 门店服务：CRUD + 按城市/坐标找最近门店（腾讯地图就近推荐的数据基础）。
 * 2026-08-08：create/update 时若地址有值而经纬度为空，自动调腾讯地图解析（MapService），免手动填 lat/lng。
 */
@Service
public class StoreService {

    private static final String MAP_CARD_THUMBNAIL = "/assets/tencent-map-card.png";

    private static final Pattern CITY_WITH_SUFFIX = Pattern.compile(
            "^([\\p{IsHan}]{2,10}?(?:自治州|地区|市|盟))");
    private static final Pattern DISTRICT_WITH_SUFFIX = Pattern.compile(
            "([\\p{IsHan}]{2,8}?(?:区|县|旗|市))");
    private static final Pattern MUNICIPALITY = Pattern.compile("^(北京|上海|天津|重庆)(?:市)?");

    private final JdbcTemplate jdbc;
    private final MapService mapService;

    public StoreService(JdbcTemplate jdbc, MapService mapService) {
        this.jdbc = jdbc;
        this.mapService = mapService;
    }

    public List<Map<String, Object>> list(String city, String address) {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("s");
        StringBuilder sql = new StringBuilder("SELECT s.*,COALESCE((SELECT string_agg(p.name,',' ORDER BY p.sort_order,p.id) " +
                "FROM store_project sp JOIN service_project p ON p.id=sp.project_id " +
                "WHERE sp.store_id=s.id AND p.status<>'disabled' AND p.parent_id IS NULL),s.projects) AS projects FROM store s");
        List<Object> args = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (scope.active()) {
            // 数据范围条件自带 ? 占位符，必须连同 args 一起加入，否则占位符与实参个数错位。
            conditions.add(scope.sql());
            args.addAll(scope.args());
        }
        if (city != null && !city.isBlank()) {
            // 同时匹配「城市」或「区县」；对 district 加「非空」保护，避免空区县门店触发
            // LIKE '%%' 恒真、被任意城市查询命中（2026-08-07 西昌店 bug）。
            String pattern = "%" + city + "%";
            conditions.add("(s.city LIKE ? OR (s.district IS NOT NULL AND s.district <> '' AND s.district LIKE ?))");
            args.add(pattern);
            args.add(pattern);
        }
        if (address != null && !address.isBlank()) {
            // 详细地址模糊搜索：对 address 加「非空」保护，避免空地址门店被 LIKE '%%' 恒真命中。
            String pattern = "%" + address + "%";
            conditions.add("(s.address IS NOT NULL AND s.address <> '' AND s.address LIKE ?)");
            args.add(pattern);
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY s.city, s.id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public long create(Map<String, Object> s) {
        resolveLatLng(s);   // 地址有值、经纬度空 → 腾讯地图自动解析
        TenantExecutionContext.Scope scope = TenantExecutionContext.current();
        Long tenantId = scope == null ? null : scope.tenantId();
        Long kfAccountId = scope == null ? null : scope.kfAccountId();
        // W25-Nit：用 RETURNING 拿新 ID（原来 SELECT max(id) 无事务且并发下可能取错）
        Long id = jdbc.queryForObject(
                "INSERT INTO store(tenant_id,kf_account_id,city,district,name,address,lat,lng,projects) VALUES (?,?,?,?,?,?,?,?,?) " +
                "RETURNING id",
                Long.class,
                tenantId, kfAccountId, s.get("city"), s.get("district"), s.get("name"), s.get("address"),
                toDouble(s.get("lat")), toDouble(s.get("lng")), s.get("projects"));
        if (id != null) syncProjectLinks(id, Objects.toString(s.get("projects"), ""));
        return id == null ? 0L : id;
    }

    public int update(long id, Map<String, Object> s) {
        resolveLatLng(s);   // 同上：编辑时地址改了也自动重解析
        StringBuilder sql = new StringBuilder(
                "UPDATE store s SET city=?, district=?, name=?, address=?, lat=?, lng=?, projects=? WHERE id=?");
        List<Object> args = new ArrayList<>();
        args.add(s.get("city"));
        args.add(s.get("district"));
        args.add(s.get("name"));
        args.add(s.get("address"));
        args.add(toDouble(s.get("lat")));
        args.add(toDouble(s.get("lng")));
        args.add(s.get("projects"));
        args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "s");
        int updated = jdbc.update(sql.toString(), args.toArray());
        if (updated > 0) syncProjectLinks(id, Objects.toString(s.get("projects"), ""));
        return updated;
    }

    /**
     * 地址有值且经纬度缺失（null/0）时，自动解析填充（腾讯地图 geocoder）。
     * 解析失败（地址不标准/未配 key）保持原值，不阻断保存。
     */
    private void resolveLatLng(Map<String, Object> s) {
        Object address = s.get("address");
        if (address == null || String.valueOf(address).isBlank()) {
            return;
        }
        Double lat = toDouble(s.get("lat"));
        Double lng = toDouble(s.get("lng"));
        if (lat != null && lat != 0 && lng != null && lng != 0) {
            return;   // 已有坐标，不重复解析
        }
        double[] coord = mapService.geocode(String.valueOf(address),
                s.get("city") == null ? null : String.valueOf(s.get("city")));
        if (coord != null) {
            s.put("lat", coord[0]);
            s.put("lng", coord[1]);
        }
    }

    public int delete(long id) {
        StringBuilder sql = new StringBuilder("DELETE FROM store s WHERE id = ?");
        List<Object> args = new ArrayList<>();
        args.add(id);
        ScopedDataAccess.appendAnd(sql, args, "s");
        return jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * 按城市找门店（腾讯地图定位前的降级方案：客户报城市/区县名直接匹配）。
     * 先精确匹配区县，再匹配城市。
     * 防御空字符串：district/city 为空时 `? LIKE '%'||''||'%'` 会变成恒真（'昆明' LIKE '%%'），
     * 导致空区县的门店被任意城市查询抢先命中（2026-08-07 西昌店 bug）。
     */
    public List<Map<String, Object>> findByLocation(String locationText) {
        LocationResolution resolution = resolveSpokenLocation(locationText);
        return resolution.status() == LocationStatus.MATCHED ? resolution.stores() : List.of();
    }

    /** 按地点和项目找门店；项目不为空时只返回项目中心明确关联的门店。 */
    public List<Map<String, Object>> findByLocation(String locationText, String project) {
        LocationResolution resolution = resolveSpokenLocation(locationText, project);
        return resolution.status() == LocationStatus.MATCHED ? resolution.stores() : List.of();
    }

    public enum LocationStatus { NONE, MATCHED, AMBIGUOUS, UNAVAILABLE }

    public record LocationResolution(LocationStatus status, String city, String district,
                                     List<Map<String, Object>> stores,
                                     List<String> candidateCities, int totalMatches) {
        public static LocationResolution none() {
            return new LocationResolution(LocationStatus.NONE, null, null, List.of(), List.of(), 0);
        }

        public boolean detected() { return status != LocationStatus.NONE; }
    }

    /**
     * 从自然口语中识别城市/区县，支持省略“市/区/县”后缀。
     * 区县在多个城市重名且用户没说城市时返回 AMBIGUOUS，由上层追问城市，绝不猜测。
     * 用户明确提供了行政区、但后台没有对应门店时返回 UNAVAILABLE，避免再次追问已提供的信息。
     */
    public LocationResolution resolveSpokenLocation(String locationText) {
        return resolveSpokenLocation(locationText, null);
    }

    /**
     * 项目已明确时，门店候选必须经过 store_project 关系过滤。
     * 子项目没有显式关联门店时返回 UNAVAILABLE，不擅自继承父项目门店。
     */
    public LocationResolution resolveSpokenLocation(String locationText, String project) {
        if (locationText == null || locationText.isBlank()) return LocationResolution.none();
        String normalized = normalizeLocation(locationText);
        if (normalized.isBlank()) return LocationResolution.none();
        List<Map<String, Object>> all = scopedLocationStores(null);
        List<Map<String, Object>> available = project == null || project.isBlank()
                ? all : scopedLocationStores(project.trim());

        List<Map<String, Object>> cityAndDistrict = all.stream()
                .filter(s -> containsRegion(normalized, s.get("city"))
                        && containsRegion(normalized, s.get("district")))
                .toList();
        if (!cityAndDistrict.isEmpty()) {
            List<Map<String, Object>> supported = retainAvailable(cityAndDistrict, available);
            return supported.isEmpty() ? unavailableFrom(cityAndDistrict, true) : matched(supported, true);
        }

        List<Map<String, Object>> districtOnly = all.stream()
                .filter(s -> containsRegion(normalized, s.get("district")))
                .toList();
        if (!districtOnly.isEmpty()) {
            List<String> cities = distinctValues(districtOnly, "city");
            if (cities.size() > 1) {
                return new LocationResolution(LocationStatus.AMBIGUOUS, null,
                        commonValue(districtOnly, "district"), List.of(), cities, districtOnly.size());
            }
            List<Map<String, Object>> supported = retainAvailable(districtOnly, available);
            return supported.isEmpty() ? unavailableFrom(districtOnly, true) : matched(supported, true);
        }

        List<Map<String, Object>> cityOnly = all.stream()
                .filter(s -> containsRegion(normalized, s.get("city")))
                .toList();
        if (!cityOnly.isEmpty()) {
            List<Map<String, Object>> supported = retainAvailable(cityOnly, available);
            return supported.isEmpty() ? unavailableFrom(cityOnly, false) : matched(supported, false);
        }

        LocationHint explicitLocation = extractExplicitLocation(locationText);
        if (explicitLocation == null) return LocationResolution.none();
        return new LocationResolution(LocationStatus.UNAVAILABLE,
                explicitLocation.city(), explicitLocation.district(), List.of(), List.of(), 0);
    }

    private List<Map<String, Object>> scopedLocationStores(String project) {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("s");
        StringBuilder sql = new StringBuilder(
                "SELECT s.* FROM store s WHERE s.city IS NOT NULL AND s.city<>''");
        List<Object> args = new ArrayList<>();
        if (project != null && !project.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM store_project sp " +
                    "JOIN service_project p ON p.id=sp.project_id " +
                    "WHERE sp.store_id=s.id AND p.status='published' AND p.name=?)");
            args.add(project.trim());
        }
        if (scope.active()) {
            sql.append(" AND ").append(scope.sql());
            args.addAll(scope.args());
        }
        sql.append(" ORDER BY s.city,s.district,s.id");
        return args.isEmpty()
                ? jdbc.queryForList(sql.toString())
                : jdbc.queryForList(sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> retainAvailable(List<Map<String, Object>> detected,
                                                       List<Map<String, Object>> available) {
        Set<String> ids = available.stream()
                .map(store -> Objects.toString(store.get("id"), ""))
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return detected.stream()
                .filter(store -> ids.contains(Objects.toString(store.get("id"), "")))
                .toList();
    }

    private LocationResolution unavailableFrom(List<Map<String, Object>> detected, boolean includeDistrict) {
        return new LocationResolution(LocationStatus.UNAVAILABLE,
                commonValue(detected, "city"), includeDistrict ? commonValue(detected, "district") : null,
                List.of(), List.of(), 0);
    }

    private record LocationHint(String city, String district) {}

    /**
     * 只识别带行政区后缀、直辖市名称，或“我在/住在/位于”后的明确地点，
     * 不把“淡斑”“垃圾分类”等任意短文本误当成城市。
     */
    private LocationHint extractExplicitLocation(String locationText) {
        String candidate = normalizeLocation(locationText).toLowerCase(java.util.Locale.ROOT);
        if (candidate.isBlank()) return null;

        candidate = candidate.replaceFirst(
                "^(?:请问|麻烦)?(?:我|本人)?(?:现在|目前)?(?:住在|住|位于|在|来自)", "");
        candidate = candidate.replaceFirst("^(?:我想|想|请|麻烦)?(?:查|查询|找|了解)", "");
        for (String stop : List.of("附近", "周边", "门店", "地址", "位置", "怎么走", "哪里有", "在哪")) {
            int index = candidate.indexOf(stop);
            if (index >= 0) candidate = candidate.substring(0, index);
        }
        candidate = candidate.replaceFirst("的$", "");
        if (candidate.isBlank()) return null;

        String city = null;
        int cityEnd = 0;
        Matcher municipality = MUNICIPALITY.matcher(candidate);
        if (municipality.find()) {
            city = municipality.group(1);
            cityEnd = municipality.end();
        } else {
            Matcher cityMatcher = CITY_WITH_SUFFIX.matcher(candidate);
            if (cityMatcher.find()) {
                city = stripRegionSuffix(cityMatcher.group(1));
                cityEnd = cityMatcher.end();
            }
        }

        String district = null;
        Matcher districtMatcher = DISTRICT_WITH_SUFFIX.matcher(candidate.substring(cityEnd));
        if (districtMatcher.find()) district = districtMatcher.group(1);

        return city == null && district == null ? null : new LocationHint(city, district);
    }

    private LocationResolution matched(List<Map<String, Object>> matches, boolean includeDistrict) {
        List<Map<String, Object>> limited = matches.stream().limit(5).toList();
        return new LocationResolution(LocationStatus.MATCHED,
                commonValue(matches, "city"), includeDistrict ? commonValue(matches, "district") : null,
                limited, List.of(), matches.size());
    }

    private List<String> distinctValues(List<Map<String, Object>> stores, String key) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, Object> store : stores) {
            String value = Objects.toString(store.get(key), "").trim();
            if (!value.isBlank()) values.add(value);
        }
        return List.copyOf(values);
    }

    private String commonValue(List<Map<String, Object>> stores, String key) {
        List<String> values = distinctValues(stores, key);
        return values.size() == 1 ? values.get(0) : null;
    }

    private boolean containsRegion(String normalizedText, Object rawRegion) {
        String region = normalizeLocation(Objects.toString(rawRegion, ""));
        if (region.isBlank()) return false;
        String shortName = stripRegionSuffix(region);
        return normalizedText.contains(region)
                || (shortName.length() >= 2 && normalizedText.contains(shortName));
    }

    private String stripRegionSuffix(String region) {
        String result = region;
        for (String suffix : List.of("特别行政区", "自治州", "地区", "市", "区", "县", "州")) {
            if (result.endsWith(suffix) && result.length() > suffix.length()) {
                result = result.substring(0, result.length() - suffix.length());
                break;
            }
        }
        return result;
    }

    private String normalizeLocation(String value) {
        return value == null ? "" : value.replaceAll("[\\p{P}\\p{S}\\s]", "");
    }

    /** 按坐标找最近门店（Haversine 公式粗算，门店量级用 SQL 算足够） */
    public List<Map<String, Object>> findNearest(double lat, double lng, int limit) {
        return findNearest(lat, lng, limit, null);
    }

    /** 按坐标找最近且明确支持指定项目的门店。 */
    public List<Map<String, Object>> findNearest(double lat, double lng, int limit, String project) {
        ScopedDataAccess.Condition scope = ScopedDataAccess.condition("s");
        List<Object> args = new ArrayList<>(List.of(lat, lng, lat));
        StringBuilder filters = new StringBuilder(" FROM store s WHERE lat IS NOT NULL");
        if (project != null && !project.isBlank()) {
            filters.append(" AND EXISTS (SELECT 1 FROM store_project sp " +
                    "JOIN service_project p ON p.id=sp.project_id " +
                    "WHERE sp.store_id=s.id AND p.status='published' AND p.name=?)");
            args.add(project.trim());
        }
        if (scope.active()) {
            filters.append(" AND ").append(scope.sql());
        }
        args.addAll(scope.args());
        args.add(limit);
        return jdbc.queryForList(
                "SELECT *, (6371 * acos(LEAST(1.0, cos(radians(?)) * cos(radians(lat)) * " +
                        "cos(radians(lng) - radians(?)) + sin(radians(?)) * sin(radians(lat))))) AS distance_km " +
                        filters + " ORDER BY distance_km LIMIT ?", args.toArray());
    }

    /**
     * 生成腾讯地图地址名片链接：门店有坐标时展示带名称和地址的位置标记；
     * 历史门店缺坐标时降级为腾讯地图地址搜索，确保地址仍有可点击入口。
     */
    public String tencentAddressCardUrl(Map<String, Object> store) {
        if (store == null || store.isEmpty()) return "";
        String name = Objects.toString(store.get("name"), "门店").trim();
        if (name.isBlank()) name = "门店";
        String address = Objects.toString(store.get("address"), "").trim();
        Double lat = coordinate(store.get("lat"));
        Double lng = coordinate(store.get("lng"));
        if (lat != null && lng != null && Math.abs(lat) <= 90 && Math.abs(lng) <= 180) {
            return "https://apis.map.qq.com/uri/v1/marker?marker=coord:" + lat + "," + lng
                    + ";title:" + encode(name) + ";addr:" + encode(address) + "&referer=aics";
        }
        if (address.isBlank()) return "";
        String city = Objects.toString(store.get("city"), "").trim();
        String region = city.isBlank() ? "" : "&region=" + encode(city);
        return "https://apis.map.qq.com/uri/v1/search?keyword="
                + encode((name + " " + address).trim()) + region + "&referer=aics";
    }

    /** 门店编辑页继续提交逗号文本，服务端把它同步成 store_project 关系。
     * 若文本中的项目是主项目（parent_id IS NULL），则自动把它下面所有启用的子项目一并关联。 */
    private void syncProjectLinks(long storeId, String projectNames) {
        jdbc.update("DELETE FROM store_project WHERE store_id=?", storeId);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String value : projectNames.split("[,，、]+")) {
            if (!value.isBlank()) names.add(value.trim());
        }
        for (String name : names) {
            List<Map<String, Object>> matched = jdbc.queryForList(
                    "SELECT p.id,p.parent_id FROM service_project p JOIN store s ON s.id=? " +
                            "WHERE p.name=? AND p.status<>'disabled' " +
                            "AND (p.tenant_id IS NULL OR p.tenant_id IS NOT DISTINCT FROM s.tenant_id) " +
                            "AND (p.kf_account_id IS NULL OR p.kf_account_id IS NOT DISTINCT FROM s.kf_account_id) " +
                            "ORDER BY p.tenant_id NULLS LAST,p.kf_account_id NULLS LAST LIMIT 1",
                    storeId, name);
            if (matched.isEmpty()) continue;
            Object idValue = matched.get(0).get("id");
            Long projectId = idValue instanceof Number n ? n.longValue() : null;
            if (projectId == null) continue;
            jdbc.update("INSERT INTO store_project(store_id,project_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                    storeId, projectId);
            if (matched.get(0).get("parent_id") == null) {
                List<Long> childIds = jdbc.queryForList(
                        "SELECT id FROM service_project WHERE parent_id=? AND status<>'disabled' ORDER BY sort_order,id",
                        Long.class, projectId);
                for (Long childId : childIds) {
                    jdbc.update("INSERT INTO store_project(store_id,project_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                            storeId, childId);
                }
            }
        }
    }

    /** 将门店数据转换成网页和企微都能使用的腾讯地图图文卡片。 */
    public LinkCard tencentAddressCard(Map<String, Object> store) {
        String url = tencentAddressCardUrl(store);
        if (url.isBlank()) return null;
        String name = Objects.toString(store.get("name"), "门店").trim();
        if (name.isBlank()) name = "门店";
        String address = Objects.toString(store.get("address"), "").trim();
        String description = address.isBlank() ? "点击查看腾讯地图地址" : address;
        return new LinkCard(name, description, url, MAP_CARD_THUMBNAIL);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private Double coordinate(Object value) {
        if (value == null) return null;
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            // W25-Nit：不合法数字 → 业务异常（全局 handler 转 400），而不是裸 500
            throw new IllegalArgumentException("经纬度必须是数字：" + s);
        }
    }
}

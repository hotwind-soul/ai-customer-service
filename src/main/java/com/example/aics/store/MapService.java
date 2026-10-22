package com.example.aics.store;

import com.example.aics.config.KeyConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 腾讯地图 WebService 地理编码（需求文档第五章"地图API优先对接腾讯"）。
 *
 * 用途：门店管理中输入地址 → 自动解析经纬度（免手动填 lat/lng），
 * 数据供 /stores/nearby 就近推荐与 /api/conversations 定位使用。
 *
 * 调用：GET https://apis.map.qq.com/ws/geocoder/v1/?address=..&key=..
 * key 从管理后台"设置"页配置（system_config map.tencent_key，lbs.qq.com 免费申请）；
 * 未配置时 geocode 返回 null，不阻塞保存。
 */
@Service
public class MapService {

    private static final Logger log = LoggerFactory.getLogger(MapService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GEOCODER_URL = "https://apis.map.qq.com/ws/geocoder/v1/";

    private final KeyConfigService keys;
    private final HttpClient http;

    public MapService(KeyConfigService keys) {
        this.keys = keys;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        if (!isConfigured()) {
            log.warn("腾讯地图 key 未配置（管理后台设置页 map.tencent_key），地址自动解析经纬度暂不可用（门店可手动填 lat/lng）");
        }
    }

    public boolean isConfigured() {
        return !keys.get("map.tencent_key", "").isBlank();
    }

    /**
     * JS SDK 用的地图 key（与 WebService 同一把）：门店地图选点组件加载 gljs 时使用。
     * JS key 本身是公开的（靠 lbs.qq.com 后台的域名白名单防盗用），返回给登录后的管理页无泄露风险。
     */
    public String jsKey() {
        return keys.get("map.tencent_key", "");
    }

    /**
     * 地址 → [lat, lng]（腾讯地图地理编码）。
     * @param address 详细地址（如"云南省昆明市官渡区星耀路99号"）
     * @param city    可选城市名（如"昆明"），提高解析准确率；可传 null
     * @return [lat, lng]；解析失败或未配置 key 返回 null
     */
    public double[] geocode(String address, String city) {
        String tencentKey = keys.get("map.tencent_key", "");
        if (tencentKey.isBlank() || address == null || address.isBlank()) {
            return null;
        }
        try {
            StringBuilder url = new StringBuilder(GEOCODER_URL)
                    .append("?address=").append(URLEncoder.encode(address.trim(), StandardCharsets.UTF_8))
                    .append("&key=").append(tencentKey);
            if (city != null && !city.isBlank()) {
                url.append("&region=").append(URLEncoder.encode(city.trim(), StandardCharsets.UTF_8));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = JSON.readTree(resp.body());
            int status = node.path("status").asInt(-1);
            if (status != 0) {
                return null;   // 地址无法解析（status!=0），交给调用方兜底
            }
            JsonNode location = node.path("result").path("location");
            if (location.isMissingNode()) {
                return null;
            }
            return new double[]{location.path("lat").asDouble(), location.path("lng").asDouble()};
        } catch (Exception e) {
            // 网络/解析异常：返回 null，门店保存不因定位失败而中断
            return null;
        }
    }
}

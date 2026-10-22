package com.example.aics.store;

import com.example.aics.message.LinkCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private MapService mapService;
    private StoreService service;

    @BeforeEach
    void setUp() {
        service = new StoreService(jdbc, mapService);
        org.mockito.Mockito.lenient().when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", 1L, "city", "成都", "district", "高新区", "name", "成都高新店", "address", "成都高新区1号"),
                Map.of("id", 2L, "city", "郑州", "district", "高新区", "name", "郑州高新店", "address", "郑州高新区1号"),
                Map.of("id", 3L, "city", "成都", "district", "武侯区", "name", "成都武侯店", "address", "成都武侯区1号")
        ));
        org.mockito.Mockito.lenient().when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("id", 1L, "city", "成都", "district", "高新区", "name", "成都高新店", "address", "成都高新区1号"),
                Map.of("id", 2L, "city", "郑州", "district", "高新区", "name", "郑州高新店", "address", "郑州高新区1号"),
                Map.of("id", 3L, "city", "成都", "district", "武侯区", "name", "成都武侯店", "address", "成都武侯区1号")
        ));
    }

    @Test
    void 城市加区县简称_优先精确到该城市区县() {
        StoreService.LocationResolution result = service.resolveSpokenLocation("我在成都高新这边");

        assertEquals(StoreService.LocationStatus.MATCHED, result.status());
        assertEquals("成都", result.city());
        assertEquals("高新区", result.district());
        assertEquals(1, result.stores().size());
        assertEquals("成都高新店", result.stores().get(0).get("name"));
    }

    @Test
    void 省略区字仍可匹配区县() {
        StoreService.LocationResolution result = service.resolveSpokenLocation("武侯");

        assertEquals(StoreService.LocationStatus.MATCHED, result.status());
        assertEquals("成都", result.city());
        assertEquals("武侯区", result.district());
    }

    @Test
    void 跨城市重名区县返回歧义而不是任选一家() {
        StoreService.LocationResolution result = service.resolveSpokenLocation("高新");

        assertEquals(StoreService.LocationStatus.AMBIGUOUS, result.status());
        assertEquals(List.of("成都", "郑州"), result.candidateCities());
        assertTrue(result.stores().isEmpty());
    }

    @Test
    void 明确提供但未录入的城市区县返回暂无门店状态() {
        StoreService.LocationResolution result = service.resolveSpokenLocation(
                "我在北京市朝阳区，附近门店地址在哪");

        assertEquals(StoreService.LocationStatus.UNAVAILABLE, result.status());
        assertEquals("北京", result.city());
        assertEquals("朝阳区", result.district());
        assertTrue(result.stores().isEmpty());
    }

    @Test
    void 未提供任何地点时仍返回未识别而不是暂无门店() {
        StoreService.LocationResolution result = service.resolveSpokenLocation("附近门店地址在哪");

        assertEquals(StoreService.LocationStatus.NONE, result.status());
    }

    @Test
    void 含有在字但实际是项目咨询时不会误识别为地点() {
        StoreService.LocationResolution result = service.resolveSpokenLocation("我在咨询淡斑");

        assertEquals(StoreService.LocationStatus.NONE, result.status());
    }

    @Test
    void 项目明确时只查询项目中心显式关联门店() {
        StoreService.LocationResolution result = service.resolveSpokenLocation("我在成都", "眼袋");

        assertEquals(StoreService.LocationStatus.MATCHED, result.status());
        verify(jdbc).queryForList(contains("FROM store_project"), any(Object[].class));
    }

    @Test
    void 城市存在但没有支持该项目的门店时返回暂无支持而非未识别地点() {
        when(jdbc.queryForList(contains("FROM store_project"), any(Object[].class))).thenReturn(List.of());

        StoreService.LocationResolution result = service.resolveSpokenLocation("成都眼袋", "眼袋");

        assertEquals(StoreService.LocationStatus.UNAVAILABLE, result.status());
        assertEquals("成都", result.city());
        assertTrue(result.stores().isEmpty());
    }

    @Test
    void 有坐标时生成腾讯地图地址名片链接() {
        String url = service.tencentAddressCardUrl(Map.of(
                "name", "成都高新店",
                "address", "成都市高新区天府大道1号",
                "lat", 30.5728,
                "lng", 104.0668));

        assertTrue(url.startsWith(
                "https://apis.map.qq.com/uri/v1/marker?marker=coord:30.5728,104.0668"));
        assertTrue(url.contains(";title:%E6%88%90%E9%83%BD%E9%AB%98%E6%96%B0%E5%BA%97"));
        assertTrue(url.contains(";addr:%E6%88%90%E9%83%BD%E5%B8%82%E9%AB%98%E6%96%B0%E5%8C%BA"));
        assertTrue(url.endsWith("&referer=aics"));
    }

    @Test
    void 无坐标时生成腾讯地图地址搜索链接() {
        String url = service.tencentAddressCardUrl(Map.of(
                "city", "成都",
                "name", "武侯店",
                "address", "武侯区人民南路1号"));

        assertTrue(url.startsWith("https://apis.map.qq.com/uri/v1/search?keyword="));
        assertTrue(url.contains("%E6%AD%A6%E4%BE%AF%E5%BA%97"));
        assertTrue(url.contains("%E4%BA%BA%E6%B0%91%E5%8D%97%E8%B7%AF1%E5%8F%B7"));
        assertTrue(url.contains("region=%E6%88%90%E9%83%BD"));
        assertTrue(url.endsWith("&referer=aics"));
    }

    @Test
    void 门店数据生成可长期展示的图文地址卡片() {
        LinkCard card = service.tencentAddressCard(Map.of(
                "city", "昆明",
                "name", "昆明首渡店",
                "address", "云南省昆明市官渡区星耀路99号"));

        assertNotNull(card);
        assertEquals("昆明首渡店", card.title());
        assertEquals("云南省昆明市官渡区星耀路99号", card.description());
        assertTrue(card.url().startsWith("https://apis.map.qq.com/uri/v1/search?keyword="));
        assertEquals("/assets/tencent-map-card.png", card.thumbnailUrl());
    }
}

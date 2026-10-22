package com.example.aics.agent;

import com.example.aics.ai.ModelGateway;
import com.example.aics.config.ConfigService;
import com.example.aics.message.LinkCard;
import com.example.aics.state.StateStore;
import com.example.aics.store.StoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private StoreService storeService;
    @Mock private ModelGateway modelGateway;
    @Mock private StateStore stateStore;
    @Mock private ConfigService config;

    private AgentService service;

    @BeforeEach
    void setUp() {
        lenient().when(config.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(config.getDouble(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        service = new AgentService(jdbc, storeService, modelGateway, stateStore, config);
    }

    private static String intent(String action, double confidence) {
        return intent(action, confidence, "", "", "");
    }

    private static String intent(String action, double confidence,
                                 String name, String project, String city) {
        return """
                {"action":"%s","secondaryAction":"chat","confidence":%s,"secondaryConfidence":0.0,"name":"%s","phone":"","project":"%s","city":"%s"}
                """.formatted(action, confidence, name, project, city).trim();
    }

    @Test
    void 明确转人工_不依赖意图模型() {
        AgentService.AgentResult result = service.tryHandle(
                "我要投诉，帮我转人工", "（无历史对话）", "user-1", "conv-1");

        assertNotNull(result);
        assertTrue(result.handoff());
        verify(modelGateway, never()).chatStructured(anyString(), anyString(), any());
        verify(stateStore, never()).getBooking(anyString());
    }

    @Test
    void 询问退款规则_不被本地规则误转人工() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("chat", 0.95));

        AgentService.AgentResult result = service.tryHandle(
                "订金能退款吗？", "（无历史对话）", "user-1", "conv-1");

        assertNull(result);
        verify(modelGateway).chatStructured(anyString(), anyString(), any());
    }

    @Test
    void 门店查询回复地址时生成结构化腾讯地图地址卡片() {
        Map<String, Object> store = Map.of(
                "name", "成都武侯店",
                "address", "成都市武侯区人民南路1号",
                "projects", "淡斑、抗衰");
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("store", 0.97, "", "", "成都"));
        when(storeService.resolveSpokenLocation("成都")).thenReturn(new StoreService.LocationResolution(
                StoreService.LocationStatus.MATCHED, "成都", null, List.of(store), List.of(), 1));
        when(storeService.tencentAddressCard(store)).thenReturn(new LinkCard(
                "成都武侯店", "成都市武侯区人民南路1号",
                "https://apis.map.qq.com/uri/v1/search?keyword=chengdu",
                "/assets/tencent-map-card.svg"));

        AgentService.AgentResult result = service.tryHandle(
                "成都门店在哪里", "（无历史对话）", "user-1", "conv-1");

        assertNotNull(result);
        assertFalse(result.handoff());
        assertTrue(result.reply().contains("成都武侯店——成都市武侯区人民南路1号"));
        assertFalse(result.reply().contains("https://"));
        assertEquals(1, result.linkCards().size());
        assertEquals("成都武侯店", result.linkCards().get(0).title());
        assertEquals("https://apis.map.qq.com/uri/v1/search?keyword=chengdu",
                result.linkCards().get(0).url());
        assertTrue(result.reply().contains("10:00-18:00"));
    }

    @Test
    void 已提供但暂无门店的地区不会再次索要城市区县() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("store", 0.97, "", "", "北京"));
        when(storeService.resolveSpokenLocation("北京")).thenReturn(
                new StoreService.LocationResolution(StoreService.LocationStatus.UNAVAILABLE,
                        "北京", null, List.of(), List.of(), 0));

        AgentService.AgentResult result = service.tryHandle(
                "北京有门店吗", "（无历史对话）", "user-1", "conv-1");

        assertNotNull(result);
        assertTrue(result.reply().contains("暂未查到北京的门店"));
        assertFalse(result.reply().contains("哪个城市、哪个区"));
    }

    @Test
    void 预约成功_先落库再更新流程状态() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("book", 0.99, "王阿姨", "", ""));

        AgentService.AgentResult result = service.tryHandle(
                "我要预约，王阿姨 13812345678", "", "user-1", "conv-1");

        assertNotNull(result);
        assertTrue(result.reply().contains("登记好"));
        var order = inOrder(jdbc, stateStore);
        order.verify(jdbc).update(contains("INSERT INTO appointment"),
                eq("user-1"), eq("王阿姨"), eq("13812345678"), anyString(), anyString(), anyString());
        order.verify(stateStore).removeBooking("conv-1");
        order.verify(stateStore).markBooked("conv-1");
    }

    @Test
    void 预约落库失败_不清理进度也不写已预约标记() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("book", 0.99, "王阿姨", "", ""));
        when(jdbc.update(contains("INSERT INTO appointment"),
                eq("user-1"), eq("王阿姨"), eq("13812345678"), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("database unavailable"));

        assertThrows(RuntimeException.class, () -> service.tryHandle(
                "我要预约，王阿姨 13812345678", "", "user-1", "conv-1"));

        verify(stateStore, never()).removeBooking(anyString());
        verify(stateStore, never()).markBooked(anyString());
    }

    @Test
    void 调试预览_可判定预约但不落库不写状态() {
        when(stateStore.getBooking("debug-conv")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("book", 0.99, "王阿姨", "抗衰", "成都"));
        when(config.get(eq("agent.workHours"), anyString())).thenReturn("09:00-22:00");

        AgentService.AgentResult result = service.tryHandlePreview(
                "我要预约，王阿姨 13812345678", "", "debug-user", "debug-conv");

        assertNotNull(result);
        assertFalse(result.handoff());
        assertTrue(result.reply().contains("登记好"));
        verify(jdbc, never()).update(contains("INSERT INTO appointment"), any(Object[].class));
        verify(stateStore, never()).putBooking(anyString(), anyMap());
        verify(stateStore, never()).removeBooking(anyString());
    }

    @Test
    void 预约等待手机号时客户追问扣费_先回答问题而不是误判拒绝留资() {
        when(stateStore.getBooking("conv-1")).thenReturn(
                new java.util.HashMap<>(Map.of("askedPhone", "1")));
        // 即使分类模型把预约上下文里的扣费追问误标成 book，也必须先回答客户问题。
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("book", 0.90));

        AgentService.AgentResult result = service.tryHandle(
                "会不会动钱", "用户：预约\n客服：方便留一下称呼和手机号吗？", "user-1", "conv-1");

        assertNull(result, "普通追问应交回主对话链回答，不能被预约槽位强制消费");
        verify(stateStore, never()).removeBooking(anyString());
        verify(stateStore, never()).putBooking(anyString(), anyMap());
        verify(jdbc, never()).update(contains("INSERT INTO appointment"), any(Object[].class));
    }

    @Test
    void 预约等待手机号时普通非问句_不再臆测客户拒绝并提议转人工() {
        when(stateStore.getBooking("conv-1")).thenReturn(
                new java.util.HashMap<>(Map.of("askedPhone", "1")));
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("chat", 0.90));

        AgentService.AgentResult result = service.tryHandle(
                "我再看看", "用户：预约\n客服：方便留一下称呼和手机号吗？", "user-1", "conv-1");

        assertNotNull(result);
        assertFalse(result.handoff());
        assertTrue(result.reply().contains("没识别到完整手机号"));
        assertFalse(result.reply().contains("不方便留电话"));
        assertFalse(result.reply().contains("帮您转人工客服备注"));
    }

    @Test
    void 低置信预约意图_只确认且不创建状态不落库() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("book", 0.40));

        AgentService.AgentResult result = service.tryHandle(
                "我在考虑预约", "（无历史对话）", "user-1", "conv-1");

        assertNotNull(result);
        assertFalse(result.handoff());
        assertTrue(result.reply().contains("确认预约"));
        verify(stateStore, never()).putBooking(anyString(), anyMap());
        verify(jdbc, never()).update(contains("INSERT INTO appointment"), any(Object[].class));
    }

    @Test
    void 分类缺少置信度_按低置信处理而不是默认执行预约() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn("{\"action\":\"book\"}");

        AgentService.AgentResult result = service.tryHandle(
                "预约收费吗", "（无历史对话）", "user-1", "conv-1");

        assertNull(result, "缺失 schema 必填字段时必须拒绝执行业务动作");
        verify(stateStore, never()).putBooking(anyString(), anyMap());
        verify(jdbc, never()).update(contains("INSERT INTO appointment"), any(Object[].class));
    }

    @Test
    void 高置信误判预约收费为预约_仍不会执行业务动作() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("book", 0.99));

        AgentService.AgentResult result = service.tryHandle(
                "预约要钱吗", "（无历史对话）", "user-1", "conv-1");

        assertNotNull(result);
        assertTrue(result.reply().contains("确认预约"));
        verify(stateStore, never()).putBooking(anyString(), anyMap());
        verify(jdbc, never()).update(contains("INSERT INTO appointment"), any(Object[].class));
    }

    @Test
    void 明确确认预约_分类器低置信或误判chat也按本地规则进入登记() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("chat", 0.20));

        AgentService.AgentResult result = service.tryHandle(
                "确认预约", "客服：如果确认继续预约，请回复确认预约。", "user-1", "conv-1");

        assertNotNull(result);
        assertTrue(result.reply().contains("称呼和手机号"));
        verify(stateStore).putBooking(eq("conv-1"), anyMap());
        verify(jdbc, never()).update(contains("INSERT INTO appointment"), any(Object[].class));
    }

    @Test
    void 低置信转人工_直接自动转避免反问激怒客户() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("handoff", 0.30));

        AgentService.AgentResult result = service.tryHandle(
                "客服能处理这个吗", "（无历史对话）", "user-1", "conv-1");

        assertNotNull(result);
        // 低置信不再反问"现在为您转人工吗"：误转占用一次人工接待，漏转直接客户流失（W26 决策）
        assertTrue(result.handoff());
        assertTrue(result.reply().contains("转人工客服"));
    }

    @Test
    void 后台旧分类提示词_代码仍强制追加置信度输出协议() {
        when(stateStore.getBooking("conv-1")).thenReturn(null);
        when(config.get(eq("agent.classifierPrompt"), anyString())).thenReturn("旧版提示词：只判断 action");
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("chat", 0.90));

        AgentService.AgentResult result = service.tryHandle(
                "客服能处理这个吗", "（无历史对话）", "user-1", "conv-1");

        assertNull(result);
        verify(modelGateway).chatStructured(argThat(system -> system.contains("旧版提示词")
                        && system.contains("secondaryConfidence")
                        && system.contains("禁止猜测客户要执行业务动作")),
                anyString(), any());
    }

    @Test
    void 已完成预约后要求取消_直接转人工处理() {
        when(stateStore.isBooked("conv-1")).thenReturn(true);

        AgentService.AgentResult result = service.tryHandle(
                "我不去了，帮我取消预约", "", "user-1", "conv-1");

        assertNotNull(result);
        assertTrue(result.handoff());
        assertTrue(result.reply().contains("取消预约"));
        verify(modelGateway, never()).chatStructured(anyString(), anyString(), any());
    }

    @Test
    void 首次议价先说明政策不直接转人工() {
        AgentService.AgentResult result = service.tryHandle(
                "价格太贵了，能便宜点吗", "（无历史对话）", "user-1", "conv-1");

        assertNotNull(result);
        assertFalse(result.handoff());
        assertTrue(result.reply().contains("不能擅自承诺额外优惠"));
    }

    @Test
    void 议价但仍想预约_说明价格边界并保留确认预约入口() {
        AgentService.AgentResult result = service.tryHandle(
                "太贵了但我还是想预约", "（无历史对话）", "user-1", "conv-1");

        assertNotNull(result);
        assertFalse(result.handoff());
        assertTrue(result.reply().contains("不能擅自承诺额外优惠"));
        assertTrue(result.reply().contains("确认预约"));
        verify(modelGateway, never()).chatStructured(anyString(), anyString(), any());
        verify(stateStore, never()).putBooking(anyString(), anyMap());
    }

    @Test
    void 重复议价仍只说明价格边界不自动转人工() {
        AgentService.AgentResult result = service.tryHandle(
                "真的不能再少一点吗", "用户：价格太贵了，能便宜点吗\n客服：以活动政策为准\n", "user-1", "conv-1");

        assertNotNull(result);
        assertFalse(result.handoff());
        assertTrue(result.reply().contains("不能擅自承诺额外优惠"));
        assertTrue(result.reply().contains("点右上角的“转人工”按钮"));
    }

    @Test
    void 打折和金额还价不依赖模型分类且不生成促销承诺() {
        for (String message : List.of("能不能打个折", "200块行不行")) {
            AgentService.AgentResult result = service.tryHandle(
                    message, "（无历史对话）", "user-1", "conv-1");

            assertNotNull(result, message);
            assertFalse(result.handoff(), message);
            assertTrue(result.reply().contains("不能擅自承诺额外优惠"), message);
            assertFalse(result.reply().contains("恢复原价"), message);
            assertFalse(result.reply().contains("留个名额"), message);
        }
        verify(modelGateway, never()).chatStructured(anyString(), anyString(), any());
    }

    @Test
    void 连续三轮议价均回答当前价格边界且金额还价不自动转人工() {
        List<String> turns = List.of("淡斑太贵了", "能便宜点不", "200块行不行");
        String history = "（无历史对话）";

        for (String message : turns) {
            AgentService.AgentResult result = service.tryHandle(
                    message, history, "user-1", "conv-1");

            assertNotNull(result, message);
            assertFalse(result.handoff(), message);
            assertTrue(result.reply().contains("已发布的活动政策"), message);
            assertTrue(result.reply().contains("转人工"), message);
            if (message.contains("200")) {
                assertTrue(result.reply().contains("不能擅自同意"));
            }
            history += "\n用户：" + message + "\n客服：" + result.reply();
        }
        verify(modelGateway, never()).chatStructured(anyString(), anyString(), any());
    }

    @Test
    void 首次项目介绍判断_正确解析同意并把标准话术与图片状态送入Prompt() {
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn("{\"introduce\":true,\"answer\":\"\"}");

        AgentService.ProjectIntroDecision result = service.decideProjectIntroduction(
                "请详细介绍淡斑活动", "用户：第一次咨询", "淡斑",
                "淡斑标准话术，活动价198元。", true, true);

        assertTrue(result.introduce());
        verify(modelGateway).chatStructured(anyString(), argThat(input -> input.contains("请详细介绍淡斑活动")
                && input.contains("淡斑标准话术，活动价198元")
                && input.contains("effectImageAvailable\":true")
                && input.contains("validPromoPosterAvailable\":true")), any());
    }

    @Test
    void 开场刚询问项目_判断输入会明确标记客户正在选择项目() {
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn("{\"introduce\":true,\"answer\":\"\"}");

        AgentService.ProjectIntroDecision result = service.decideProjectIntroduction(
                "淡斑",
                "客服开场语：请问您是想了解面部抗衰提升、眼袋、淡斑还是胡子管理呢？",
                "淡斑", "淡斑标准活动介绍", true, true);

        assertTrue(result.introduce());
        verify(modelGateway).chatStructured(anyString(), argThat(input ->
                input.contains("replyingToProjectSelectionQuestion\":true")), any());
    }

    @Test
    void 首次项目介绍判断_不同意时返回自然追问() {
        when(modelGateway.chatStructured(anyString(), anyString(), any())).thenReturn(
                "判断结果：{\"introduce\":false,\"answer\":\"您更关心淡斑的价格还是效果呢？\"}");

        AgentService.ProjectIntroDecision result = service.decideProjectIntroduction(
                "淡斑", "（无历史对话）", "淡斑", "标准活动话术", true, true);

        assertFalse(result.introduce());
        assertTrue(result.answer().contains("淡斑"));
        assertNotEquals("您更关心淡斑的价格还是效果呢？", result.answer(),
                "带前缀解释的输出不是完整 JSON，不得截取后接受");
    }

    @Test
    void 首次项目介绍判断_模型异常或非法Json时安全回退为不促销() {
        when(modelGateway.chatStructured(anyString(), anyString(), any())).thenReturn("不是 JSON");

        AgentService.ProjectIntroDecision result = service.decideProjectIntroduction(
                "淡斑", "", "淡斑", "标准活动话术", true, true);

        assertFalse(result.introduce());
        assertTrue(result.answer().contains("淡斑"));
        assertFalse(result.answer().contains("活动价"));
    }

    @Test
    void 调试预览可以使用尚未发布的分类Prompt且不读取线上Prompt() {
        when(stateStore.getBooking("debug-conv")).thenReturn(null);
        when(modelGateway.chatStructured(anyString(), anyString(), any()))
                .thenReturn(intent("chat", 0.9));

        service.tryHandlePreview("我想预约了解一下", "", "debug-user", "debug-conv", "草稿分类规则");

        verify(modelGateway).chatStructured(argThat(system -> system.startsWith("草稿分类规则")),
                anyString(), any());
        verify(config, never()).get(eq("agent.classifierPrompt"), anyString());
    }
}

package com.example.aics.chat;

import com.example.aics.agent.AgentAccountService;
import com.example.aics.agent.AgentService;
import com.example.aics.config.ConfigService;
import com.example.aics.image.ImageService;
import com.example.aics.knowledge.BusinessFactService;
import com.example.aics.knowledge.KnowledgeService;
import com.example.aics.knowledge.VectorStore;
import com.example.aics.message.LinkCard;
import com.example.aics.store.StoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ChatEngine 应答路由测试（第二批 #7，核心流水线守护）。
 *
 * 目标：不联网、不依赖数据库/模型，mock 掉外部依赖后验证分流的正确性：
 *   Agent 业务工具 > FAQ 高置信直回 > 澄清/安全拒答 > AI+RAG 生成
 * 题目数据来自评测集.xlsx 的真实问题（48 题抽样），守护"修了一个、坏了三个"的回归风险。
 * 真实正确率评测仍由 eval/ 目录的 Python 脚本（联网 + 真实知识库）负责。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatEngineRoutingTest {

    @Mock private KnowledgeService knowledgeService;
    @Mock private BusinessFactService businessFactService;
    @Mock private ConversationService conversationService;
    @Mock private UserProfileService userProfileService;
    @Mock private ImageService imageService;
    @Mock private ConfigService config;
    @Mock private AgentService agentService;
    @Mock private AgentAccountService agentAccountService;
    @Mock private StoreService storeService;

    private ChatEngine engine;

    @BeforeEach
    void setUp() {
        lenient().when(config.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(config.getDouble(anyString(), anyDouble())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        engine = new ChatEngine(knowledgeService, businessFactService, conversationService, userProfileService,
                imageService, config, agentService, agentAccountService, storeService);
        when(config.getDouble(eq("threshold.relevance"), anyDouble())).thenReturn(0.5);
        when(config.getDouble(eq("threshold.faqDirect"), anyDouble())).thenReturn(0.15);
        when(config.get(eq("refusal.answer"), anyString()))
                .thenReturn("该问题超出了我的知识范围，已为您转接人工客服。");
        when(config.get(eq("prompt.tone"), anyString())).thenReturn("");
        when(config.get(eq("agent.workHours"), anyString())).thenReturn("09:00-22:00");
        when(config.get(eq("store.businessHours"), anyString())).thenReturn("10:00-18:00");
        when(conversationService.ensureConversation(any(), any())).thenReturn("conv-1");
        when(conversationService.recentHistoryText(anyString())).thenReturn("（无历史对话）");
        when(userProfileService.getProfileText(anyString())).thenReturn("");
    }

    private VectorStore.Match faq(String content, double distance) {
        return new VectorStore.Match(1L, "FAQ数据表.xlsx", content, distance,
                null, "通用", "faq", "low", true, "hybrid", 0.03);
    }

    private VectorStore.Match doc(String content, double distance) {
        return new VectorStore.Match(1L, "术后恢复.md", content, distance);
    }

    // ==================== 前置层：情绪与安全 ====================

    @Test
    void 首次辱骂_直接降温且不调用业务检索或模型() {
        ChatEngine.Prepared p = engine.prepare("你这个垃 圾！客 服，真是废物", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertNotNull(p.agentAnswer());
        assertTrue(p.agentAnswer().contains("生气"));
        assertNull(p.prompt());
        assertTrue(p.candidates().isEmpty());
        assertEquals("未分析", p.understanding().reason());
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
        verify(agentService, never()).tryHandlePreview(anyString(), anyString(), anyString(), anyString());
        verify(businessFactService, never()).tryAnswer(anyString(), any());
        verify(knowledgeService, never()).hasKnowledge();
        verify(knowledgeService, never()).search(anyString(), anyInt(), nullable(String.class), nullable(String.class));
    }

    @Test
    void 客服真垃圾倒装表达_首次仍先安抚而不转人工() {
        ChatEngine.Prepared p = engine.prepare("你们这个客服真垃圾", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("生气"));
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 垃圾分类等普通表达_不会被组合辱骂规则误伤() {
        ChatEngine.Prepared p = engine.prepare("你知道垃圾分类有什么要求吗", null, "user1");

        assertNotEquals("SAFETY", p.replyType());
    }

    @Test
    void 历史已有辱骂_本轮使用重复辱骂边界话术() {
        when(conversationService.recentHistoryText(anyString()))
                .thenReturn("用户：你这个垃圾客服\n客服：请告诉我具体问题\n");

        ChatEngine.Prepared p = engine.prepare("你真是废物", "conv-1", "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("停止辱骂"));
        assertTrue(p.agentAnswer().contains("转人工"));
    }

    @Test
    void 同一项目维持时间连续询问_第二次改为明确解释并提供人工核实() {
        when(conversationService.recentHistoryText(anyString())).thenReturn(
                "用户：淡斑做完能维持多久\n" +
                "客服：效果和维持时间因人而异。\n");

        ChatEngine.Prepared p = engine.prepare("淡斑做完能维持多久", "conv-1", "user1");

        assertEquals("CLARIFY", p.replyType());
        assertTrue(p.agentAnswer().contains("刚才可能没讲清楚"));
        assertTrue(p.agentAnswer().contains("没有统一的固定时长"));
        assertTrue(p.agentAnswer().contains("转人工"));
        verify(knowledgeService, never()).hasKnowledge();
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 询问客服在线时间_直接回答客服时间且不触发转人工() {
        when(businessFactService.tryAnswer(eq("人工客服在线时间"), any()))
                .thenReturn(new BusinessFactService.StructuredAnswer(
                        "人工客服在线时间是每天09:00-22:00。", "hours.agent.workHours"));

        ChatEngine.Prepared p = engine.prepare("人工客服在线时间", null, "user1");

        assertEquals("FACT", p.replyType());
        assertTrue(p.directAnswer().contains("09:00-22:00"));
        assertFalse(p.directAnswer().contains("10:00-18:00"));
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 暴力威胁_调试预览自动转人工但不产生真实副作用() {
        ChatEngine.Prepared p = engine.prepareDebug(
                "再不处理我就带刀去你们店", "debug-conv", "debug-user", null, null);

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("威胁"));
        assertTrue(p.agentAnswer().contains("调试预览"));
        verify(conversationService, never()).ensureConversation(any(), any());
        verify(agentAccountService, never()).tryAssignRoundRobin(anyString());
        verify(conversationService, never()).setStatus(anyString(), anyString());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 中文敏感词库新增辱骂词_直接进入情绪安全路由() {
        ChatEngine.Prepared p = engine.prepare("你真是狗娘养的", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("生气"));
        verify(knowledgeService, never()).hasKnowledge();
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 词库无关分类未导入_正常咨询不会被当作辱骂() {
        ChatEngine.Prepared p = engine.prepare("咖啡因有什么作用", null, "user1");

        assertNotEquals("SAFETY", p.replyType());
        verify(knowledgeService).hasKnowledge();
    }

    @Test
    void 辱骂同时明确投诉_保留Agent转人工流程() {
        when(agentService.tryHandlePreview(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentService.AgentResult("转人工", true));

        ChatEngine.Prepared p = engine.prepareDebug(
                "傻逼，我要投诉", "debug-conv", "debug-user", null, null);

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("调试预览"));
        verify(agentService).tryHandlePreview(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 网页自定义辱骂关键词和回复会立即生效() {
        when(config.get(eq("safety.abuseKeywords"), anyString())).thenReturn("憨包");
        when(config.get(eq("safety.abuseFirstResponse"), anyString())).thenReturn("请文明沟通，我会继续帮您处理。");

        ChatEngine.Prepared p = engine.prepare("你这个憨 包", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertEquals("请文明沟通，我会继续帮您处理。", p.agentAnswer());
    }

    @Test
    void 情绪安全消息落库但不触发客户画像提取() {
        engine.persist("conv-1", "垃圾客服", "请文明沟通", "SAFETY");

        verify(conversationService).saveMessage("conv-1", "user", "垃圾客服");
        verify(conversationService).saveMessage("conv-1", "assistant", "请文明沟通", "SAFETY");
        verify(conversationService, never()).getUserId(anyString());
        verify(userProfileService, never()).onUserMessage(anyString());
    }

    // ==================== 第一层：Agent 业务工具（P0-2，必须优先于 FAQ） ====================

    @Test
    void 预约登记_Agent拦截优先于FAQ直回_不截胡() {
        // FAQ 里也有"怎么预约"的高置信条目，但 Agent 必须先处理预约意图（漏单修复 P0-2）
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(faq("问：怎么预约？\n答：点击预约登记，老师联系您确认档期。\n分类：预约", 0.05)));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentService.AgentResult("好的，我帮您登记预约。方便留一下您的称呼和手机号吗？", false));

        ChatEngine.Prepared p = engine.prepare("我要预约", null, "user1");

        assertNotNull(p.agentAnswer(), "Agent 拦截后应有回复话术");
        assertEquals("AGENT", p.replyType());
        assertNull(p.directAnswer(), "预约意图不能被 FAQ 高置信条目截胡");
        assertNull(p.prompt());
        verify(knowledgeService, never()).hasKnowledge();
        verify(knowledgeService, never()).search(anyString(), anyInt(), nullable(String.class), nullable(String.class));
    }

    @Test
    void 主动要求转人工_网页渠道不分配坐席并说明迁移() {
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentService.AgentResult("好的，已为您转接人工客服。", true));
        // 网页渠道人工接待已迁移至企微原生接待：即使配置成当前时间之外，也不进入本站排队。
        when(config.get(eq("agent.workHours"), anyString())).thenReturn("00:00-00:01");

        ChatEngine.Prepared p = engine.prepare("我要转人工", null, "user1");

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("暂不支持转接人工"));
        assertTrue(p.agentAnswer().contains("企业微信"));
        verify(agentAccountService, never()).tryAssignRoundRobin(anyString());
        verify(conversationService, never()).setStatus(anyString(), eq("need_human"));
        verify(conversationService, never()).setStatus(anyString(), eq("human"));
    }

    @Test
    void 转人工_即使存在在线坐席_网页渠道也不再分配() {
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentService.AgentResult("转人工", true));
        when(agentAccountService.tryAssignRoundRobin("conv-1")).thenReturn("小李");

        ChatEngine.Prepared p = engine.prepare("请转人工", null, "user1");

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("暂不支持转接人工"));
        verify(agentAccountService, never()).tryAssignRoundRobin(anyString());
        verify(conversationService, never()).setStatus(anyString(), eq("human"));
    }

    @Test
    void 企微转人工_只标记待接入不分配网页坐席() {
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentService.AgentResult("转人工", true));

        ChatEngine.Prepared p = engine.prepareForWeCom("请转人工", null, "user1", null, null);

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("正在为您转接"));
        verify(conversationService).setStatus("conv-1", "need_human");
        verify(agentAccountService, never()).tryAssignRoundRobin(anyString());
    }

    @Test
    void 调试转人工_只返回预览不分配坐席() {
        when(agentService.tryHandlePreview(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentService.AgentResult("转人工", true));

        ChatEngine.Prepared p = engine.prepareDebug(
                "我要转人工", "debug-conv", "debug-user", null, null);

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("调试预览"));
        verify(conversationService, never()).ensureConversation(any(), any());
        verify(agentAccountService, never()).tryAssignRoundRobin(anyString());
        verify(conversationService, never()).setStatus(anyString(), anyString());
    }

    // ==================== 第二层：FAQ 高置信直回 ====================

    @Test
    void 明确要求详细活动介绍_AI同意后发送标准话术和两张图片() {
        when(businessFactService.initialProjectPitch("淡斑"))
                .thenReturn(new BusinessFactService.StructuredAnswer(
                        "淡斑项目完整活动介绍，活动价198元。", "price.xlsx#淡斑.sale_copy"));
        when(imageService.matchInitialProjectAssets(eq("淡斑"), eq("conv-1"), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(List.of(
                Map.of("name", "淡斑效果图", "url", "/effect.jpg", "type", "effect"),
                Map.of("name", "淡斑优惠海报", "url", "/promo.jpg", "type", "promo")));
        when(agentService.decideProjectIntroduction(anyString(), anyString(), eq("淡斑"),
                contains("活动价198元"), eq(true), eq(true), nullable(String.class)))
                .thenReturn(new AgentService.ProjectIntroDecision(true, ""));

        ChatEngine.Prepared p = engine.prepare("请详细介绍一下淡斑活动", null, "user1");

        assertEquals("INTRO", p.replyType());
        assertTrue(p.directAnswer().startsWith("淡斑项目完整活动介绍，活动价198元。"));
        assertTrue(p.directAnswer().contains("效果图和当前有效的优惠海报"));
        assertEquals(2, p.images().size());
        assertEquals("effect", p.images().get(0).get("type"));
        assertEquals("promo", p.images().get(1).get("type"));
        assertEquals("淡斑", p.introducedProject());
        assertEquals("INTRODUCE", p.projectIntroDecision());
        verify(businessFactService, never()).tryAnswer(anyString(), any());
    }

    @Test
    void 首次活动介绍只有效果图_仍由AI判断并发送单张图片() {
        when(businessFactService.initialProjectPitch("淡斑"))
                .thenReturn(new BusinessFactService.StructuredAnswer("淡斑标准活动介绍。", "price.xlsx#淡斑.sale_copy"));
        when(imageService.matchInitialProjectAssets(eq("淡斑"), eq("conv-1"), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(List.of(Map.of("name", "淡斑效果图", "url", "/effect.jpg", "type", "effect")));
        when(agentService.decideProjectIntroduction(anyString(), anyString(), eq("淡斑"),
                contains("淡斑标准活动介绍"), eq(true), eq(false), nullable(String.class)))
                .thenReturn(new AgentService.ProjectIntroDecision(true, ""));

        ChatEngine.Prepared p = engine.prepare("请详细介绍一下淡斑活动", null, "user1");

        assertEquals("INTRO", p.replyType());
        assertEquals(1, p.images().size());
        assertEquals("effect", p.images().get(0).get("type"));
        assertTrue(p.directAnswer().contains("项目效果图也一起发给您了"));
        verify(agentService).decideProjectIntroduction(
                anyString(), anyString(), eq("淡斑"), anyString(), eq(true), eq(false), nullable(String.class));
    }

    @Test
    void 已经介绍过同一项目_不重复进行首次介绍判断() {
        when(conversationService.hasProjectIntroduced("conv-1", "淡斑")).thenReturn(true);

        ChatEngine.Prepared p = engine.prepare("请详细介绍一下淡斑活动", "conv-1", "user1");

        assertNotEquals("INTRO", p.replyType());
        verify(businessFactService, never()).initialProjectPitch(anyString());
        verify(agentService, never()).decideProjectIntroduction(
                anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean(), nullable(String.class));
    }

    @Test
    void 后续询问价格_没有主动索图则不再发送图片() {
        when(conversationService.recentHistoryText(anyString()))
                .thenReturn("用户：我想了解淡斑\n客服：淡斑项目活动介绍\n");
        when(businessFactService.tryAnswer(eq("多少钱"), any(MessageUnderstanding.Result.class)))
                .thenReturn(new BusinessFactService.StructuredAnswer("淡斑活动价198元。", "price.淡斑"));

        ChatEngine.Prepared p = engine.prepare("多少钱", "conv-1", "user1");

        assertEquals("FACT", p.replyType());
        assertTrue(p.images().isEmpty());
        verify(imageService, never()).matchInitialProjectAssets(anyString(), anyString(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(imageService, never()).matchByRequest(anyString(), nullable(String.class), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void 首次询问项目但已经提供城市_不发送saleCopy() {
        Map<String, Object> store = Map.of("name", "成都店", "address", "成都市测试路1号");
        when(storeService.resolveSpokenLocation("成都淡斑", "淡斑")).thenReturn(new StoreService.LocationResolution(
                StoreService.LocationStatus.MATCHED, "成都", null,
                List.of(store), List.of(), 1));
        when(storeService.tencentAddressCard(store)).thenReturn(new LinkCard(
                "成都店", "成都市测试路1号",
                "https://apis.map.qq.com/uri/v1/search?keyword=chengdu",
                "/assets/tencent-map-card.svg"));

        ChatEngine.Prepared p = engine.prepare("成都淡斑", null, "user1");

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("成都店"));
        assertFalse(p.agentAnswer().contains("https://"));
        assertEquals(1, p.linkCards().size());
        assertEquals("成都店", p.linkCards().get(0).title());
        verify(businessFactService, never()).initialProjectPitch(anyString());
    }

    @Test
    void 只口述区县_直接查询门店管理地址() {
        Map<String, Object> store = Map.of(
                "name", "武侯店", "address", "武侯区人民南路1号", "projects", "淡斑");
        when(storeService.resolveSpokenLocation("我在武侯", null)).thenReturn(new StoreService.LocationResolution(
                StoreService.LocationStatus.MATCHED, "成都", "武侯区",
                List.of(store), List.of(), 1));
        when(storeService.tencentAddressCard(store)).thenReturn(new LinkCard(
                "武侯店", "武侯区人民南路1号",
                "https://apis.map.qq.com/uri/v1/search?keyword=wuhou",
                "/assets/tencent-map-card.svg"));

        ChatEngine.Prepared p = engine.prepare("我在武侯", null, "user1");

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("武侯店"));
        assertTrue(p.agentAnswer().contains("人民南路1号"));
        assertFalse(p.agentAnswer().contains("https://"));
        assertEquals("https://apis.map.qq.com/uri/v1/search?keyword=wuhou",
                p.linkCards().get(0).url());
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 区县跨城市重名_追问城市而不是猜门店() {
        when(storeService.resolveSpokenLocation("我在高新", null)).thenReturn(new StoreService.LocationResolution(
                StoreService.LocationStatus.AMBIGUOUS, null, "高新区", List.of(),
                List.of("成都", "郑州"), 4));

        ChatEngine.Prepared p = engine.prepare("我在高新", null, "user1");

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("成都、郑州"));
        assertTrue(p.agentAnswer().contains("城市名称"));
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 已提供但暂无门店的城市区县_明确告知无店而不重复追问() {
        when(storeService.resolveSpokenLocation("我在北京市朝阳区，附近门店地址在哪", null))
                .thenReturn(new StoreService.LocationResolution(
                        StoreService.LocationStatus.UNAVAILABLE, "北京", "朝阳区",
                        List.of(), List.of(), 0));

        ChatEngine.Prepared p = engine.prepare("我在北京市朝阳区，附近门店地址在哪", null, "user1");

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("暂未查到北京朝阳区的门店"));
        assertFalse(p.agentAnswer().contains("哪个城市、哪个区"));
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void FAQ直回_高置信命中_返回标准答案不调模型() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(faq("### 问: 到店需要带什么材料？\n- 答案: 请携带本人有效证件和已有的相关资料。\n类别: 到店", 0.10)));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("到店需要带什么材料？", null, "user1");

        assertNotNull(p.directAnswer(), p.replyType() + ":" + p.agentAnswer());
        assertEquals("FAQ", p.replyType());
        assertTrue(p.directAnswer().contains("有效证件"), "标准答案应完整返回");
        assertFalse(p.directAnswer().contains("分类"), "分类尾巴应被截掉");
        assertNull(p.prompt(), "直回不调大模型");
        assertFalse(p.refused());
    }

    @Test
    void 结构化价格_优先于Agent和知识检索() {
        when(businessFactService.tryAnswer(eq("淡斑多少钱"), any(MessageUnderstanding.Result.class)))
                .thenReturn(new BusinessFactService.StructuredAnswer("淡斑活动价198元。", "price.淡斑"));

        ChatEngine.Prepared p = engine.prepare("淡斑多少钱", null, "user1");

        assertEquals("FACT", p.replyType());
        assertEquals("淡斑活动价198元。", p.directAnswer());
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void FAQ直回_第一名不是FAQ时仍可选择审核通过的FAQ() {
        VectorStore.Match offer = new VectorStore.Match(9L, "到店说明.md", "门店服务说明", 0.08,
                null, "到店", "reference", "low", false, "vector", 0.03);
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(offer, faq("问：需要准备什么材料？\n答：请携带本人有效证件。\n分类：到店", 0.10)));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("需要准备什么材料", null, "user1");

        assertEquals("FAQ", p.replyType());
        assertEquals("请携带本人有效证件。", p.directAnswer());
    }

    @Test
    void 高风险效果问题_前置安全回答且不进入知识检索() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(faq("问：淡斑会反弹吗？\n答：永久不会反弹。\n分类：淡斑", 0.05)));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("淡斑效果会反弹吗", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("不能保证"));
        assertNull(p.prompt());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 术后饮酒问题_前置使用通用护理安全话术() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(faq("问：做完能喝酒吗？\n答：做完喝什么都可以。\n分类：通用", 0.05)));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("做完能喝酒吗", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("书面指引"));
        assertNull(p.prompt());
        verify(knowledgeService, never()).hasKnowledge();
    }

    // ==================== 第三层：澄清 / 安全拒答 ====================

    @Test
    void 拒答_知识库为空_不虚假声称已转人工() {
        when(knowledgeService.hasKnowledge()).thenReturn(false);
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("帮我写一首关于抗衰的诗", null, "user1");

        assertTrue(p.refused());
        assertEquals("REFUSAL", p.replyType());
        assertNotNull(p.agentAnswer());
        assertFalse(p.agentAnswer().contains("已为您转接"));
        assertTrue(p.agentAnswer().contains("可靠资料"));
    }

    @Test
    void 拒答_检索无命中_返回安全话术() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class))).thenReturn(List.of());
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("割双眼皮多少钱", null, "user1");

        assertTrue(p.refused());
        assertEquals("REFUSAL", p.replyType());
        assertNotNull(p.agentAnswer());
    }

    @Test
    void 拒答_不占用人工队列() {
        when(knowledgeService.hasKnowledge()).thenReturn(false);
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("帮我写首诗", null, "user1");

        assertTrue(p.refused());
        verify(agentAccountService, never()).tryAssignRoundRobin(anyString());
        verify(conversationService, never()).setStatus(anyString(), eq("need_human"));
    }

    @Test
    void 模糊问法_无命中时先澄清不拒答() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class))).thenReturn(List.of());
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("多少钱？", null, "user1");

        assertFalse(p.refused());
        assertEquals("CLARIFY", p.replyType());
        assertTrue(p.agentAnswer().contains("哪个项目"));
        verify(agentAccountService, never()).tryAssignRoundRobin(anyString());
        verify(knowledgeService, never()).hasKnowledge();
        verify(knowledgeService, never()).search(anyString(), anyInt(), nullable(String.class), nullable(String.class));
    }

    @Test
    void 项目简称_定向追问该项目而不是泛问项目() {
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        when(businessFactService.initialProjectPitch("淡斑"))
                .thenReturn(new BusinessFactService.StructuredAnswer("淡斑标准活动介绍，活动价198元。", "price.xlsx#淡斑.sale_copy"));
        when(imageService.matchInitialProjectAssets(eq("淡斑"), eq("conv-1"), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(List.of(
                Map.of("name", "淡斑效果图", "url", "/effect.jpg", "type", "effect"),
                Map.of("name", "淡斑优惠海报", "url", "/promo.jpg", "type", "promo")));
        when(agentService.decideProjectIntroduction(anyString(), anyString(), eq("淡斑"),
                anyString(), eq(true), eq(true), nullable(String.class)))
                .thenReturn(new AgentService.ProjectIntroDecision(false, "您更关心淡斑的价格还是效果呢？"));

        ChatEngine.Prepared p = engine.prepare("淡斑", null, "user1");

        assertEquals("CLARIFY", p.replyType());
        assertEquals("您更关心淡斑的价格还是效果呢？", p.agentAnswer());
        assertTrue(p.images().isEmpty());
        assertEquals("CLARIFY", p.projectIntroDecision());
        assertEquals("淡斑", p.understanding().project());
        assertFalse(p.understanding().complete());
        verify(knowledgeService, never()).search(anyString(), anyInt(), nullable(String.class), nullable(String.class));
    }

    @Test
    void 开场询问想了解哪个项目_客户只回复项目名也进入详细首次介绍() {
        when(conversationService.isFirstMessage("conv-1")).thenReturn(true);
        when(config.get(eq("welcome.message"), anyString())).thenReturn(
                "您好，请问您想了解抗衰、淡斑、眼袋还是胡子项目呢？");
        when(businessFactService.initialProjectPitch("淡斑"))
                .thenReturn(new BusinessFactService.StructuredAnswer("淡斑完整标准活动介绍。", "price.xlsx#淡斑.sale_copy"));
        when(imageService.matchInitialProjectAssets(eq("淡斑"), eq("conv-1"), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(List.of(
                Map.of("name", "淡斑效果图", "url", "/effect.jpg", "type", "effect"),
                Map.of("name", "淡斑优惠海报", "url", "/promo.jpg", "type", "promo")));
        when(agentService.decideProjectIntroduction(eq("淡斑"),
                argThat(h -> h.contains("客服开场语") && h.contains("想了解抗衰、淡斑、眼袋还是胡子")),
                eq("淡斑"), anyString(), eq(true), eq(true), nullable(String.class)))
                .thenReturn(new AgentService.ProjectIntroDecision(true, ""));

        ChatEngine.Prepared p = engine.prepare("淡斑", null, "user1");

        assertEquals("INTRO", p.replyType());
        assertTrue(p.directAnswer().contains("淡斑完整标准活动介绍"));
        assertEquals(2, p.images().size());
        assertNotNull(p.welcome());
    }

    @Test
    void 首次介绍调试预览不写状态_正式持久化后才标记() {
        when(businessFactService.initialProjectPitch("淡斑"))
                .thenReturn(new BusinessFactService.StructuredAnswer("淡斑标准活动介绍。", "price.xlsx#淡斑.sale_copy"));
        when(imageService.matchInitialProjectAssets(eq("淡斑"), eq("debug-conv"), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(List.of(
                Map.of("name", "效果图", "url", "/effect.jpg", "type", "effect"),
                Map.of("name", "优惠海报", "url", "/promo.jpg", "type", "promo")));
        when(agentService.decideProjectIntroduction(anyString(), anyString(), eq("淡斑"),
                anyString(), eq(true), eq(true), nullable(String.class)))
                .thenReturn(new AgentService.ProjectIntroDecision(true, ""));

        ChatEngine.Prepared p = engine.prepareDebug(
                "详细介绍一下淡斑活动", "debug-conv", "debug-user", null, null);

        assertEquals("INTRO", p.replyType());
        verify(conversationService, never()).markProjectIntroduced(anyString(), anyString());

        engine.persist(p, "详细介绍一下淡斑活动", p.directAnswer(), p.replyType());
        verify(conversationService).markProjectIntroduced("debug-conv", "淡斑");
    }

    @Test
    void 多轮价格追问_继承上一轮项目并命中结构化事实() {
        when(conversationService.recentHistoryText(anyString()))
                .thenReturn("用户：淡斑\n客服：您想先了解淡斑的哪方面呢？\n");
        when(businessFactService.tryAnswer(eq("多少钱"), argThat(u ->
                "淡斑".equals(u.project()) && "价格".equals(u.aspect()))))
                .thenReturn(new BusinessFactService.StructuredAnswer("淡斑活动价198元。", "price.淡斑"));

        ChatEngine.Prepared p = engine.prepare("多少钱", "conv-1", "user1");

        assertEquals("FACT", p.replyType());
        assertEquals("淡斑活动价198元。", p.directAnswer());
        assertEquals("淡斑，多少钱", p.understanding().retrievalQuery());
        assertTrue(p.understanding().inheritedProject());
        verify(knowledgeService, never()).search(anyString(), anyInt(), nullable(String.class), nullable(String.class));
    }

    // ==================== 第四层：AI + RAG 生成 ====================

    @Test
    void AI生成_检索命中非FAQ_拼RAGprompt() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(doc("淡斑项目采用仪器方式，具体设备和步骤以门店公示资料为准。", 0.35)));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("淡斑是怎么做的", null, "user1");

        assertNull(p.directAnswer());
        assertFalse(p.refused());
        assertEquals("AI", p.replyType());
        assertNotNull(p.prompt());
        assertNotNull(p.chatPrompt());
        assertEquals("淡斑是怎么做的", p.chatPrompt().user(),
                "user 层只保留当前客户问题");
        assertFalse(p.chatPrompt().system().contains("淡斑是怎么做的"),
                "客户文本不得晋升为 system 指令");
        assertTrue(p.chatPrompt().context().contains("参考资料"));
        assertTrue(p.prompt().contains("参考资料"), "RAG prompt 应含参考资料段");
        assertTrue(p.prompt().contains("具体设备和步骤"), "检索到的知识应拼进参考资料");
        assertEquals(p.prompt().indexOf("【参考资料开始】"), p.prompt().lastIndexOf("【参考资料开始】"),
                "参考资料区块只能出现一次");
        assertEquals("prompt-unversioned/customer-service-rag-v1", p.chatPrompt().version());
        assertTrue(p.chatPrompt().modules().stream()
                .anyMatch(module -> "scenario.rag-answer".equals(module.id())));
        assertTrue(p.chatPrompt().modules().stream()
                .anyMatch(module -> "safety.medical".equals(module.id())));
        assertEquals(p.chatPrompt().fingerprint(), p.trace().promptHash());
        assertEquals(p.chatPrompt().modules(), p.trace().promptModules());
    }

    @Test
    void AI实验台可以预览尚未发布的系统与RAG草稿() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(doc("淡斑项目流程资料。", 0.35)));
        when(agentService.tryHandlePreview(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        ChatEngine.DebugOptions options = new ChatEngine.DebugOptions(
                null, null, null, null, null, null,
                "草稿系统身份", "草稿 RAG 规则", null, null, null, null, null);

        ChatEngine.Prepared p = engine.prepareDebug(
                "淡斑怎么做", "debug-conv", "debug-user", null, null, options);

        assertEquals("AI", p.replyType());
        assertTrue(p.chatPrompt().system().contains("草稿系统身份"));
        assertTrue(p.chatPrompt().system().contains("草稿 RAG 规则"));
    }

    @Test
    void AI生成_出网消息已脱敏() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(doc("淡斑项目流程资料。", 0.35)));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("淡斑是怎么操作的，电话13812345678", null, "user1");

        assertEquals("AI", p.replyType());
        assertNotNull(p.prompt());
        assertTrue(p.prompt().contains("138****5678"), "出网 prompt 中手机号必须打码");
        assertFalse(p.prompt().contains("13812345678"), "出网 prompt 中不得出现完整手机号");
    }

    @Test
    void 结构化价格漏过时_权威策略禁止回退到旧知识库() {
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("淡斑多少钱", null, "user1");

        assertEquals("REFUSAL", p.replyType());
        assertTrue(p.agentAnswer().contains("不引用旧知识库价格"));
        assertEquals("PRICE", p.trace().authorityDomain());
        assertEquals("PROJECT_CENTER", p.trace().authoritySource());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 路由追踪记录权威来源且不保存用户原文() {
        when(businessFactService.tryAnswer(eq("淡斑多少钱"), any()))
                .thenReturn(new BusinessFactService.StructuredAnswer("淡斑活动价198元。", "project-offer#淡斑"));
        when(conversationService.saveMessage("conv-1", "assistant", "淡斑活动价198元。", "FACT"))
                .thenReturn(42L);

        ChatEngine.Prepared p = engine.prepare("淡斑多少钱", null, "user1");
        engine.persist(p, "淡斑多少钱", p.directAnswer(), p.replyType());

        assertEquals("FACT", p.trace().route());
        assertEquals("PROJECT_CENTER", p.trace().authoritySource());
        assertFalse(p.trace().modelRequired());
        verify(conversationService).saveDecisionTrace(eq(42L), argThat(json ->
                json.contains("\"route\":\"FACT\"")
                        && json.contains("\"authoritySource\":\"PROJECT_CENTER\"")
                        && !json.contains("淡斑多少钱")));
    }

    @Test
    void 统一终检_非业务路由不允许伪造已转人工() {
        ChatEngine.Prepared p = new ChatEngine.Prepared(
                "conv-1", false, null, List.of(), List.of(), "已为您转接人工客服。",
                List.of(), List.of(), null, "FAQ", null);

        String answer = engine.finalizeAnswer(p, p.directAnswer());

        assertFalse(answer.contains("已为您转接"));
        assertTrue(answer.contains("请回复“转人工”"));
    }

    @Test
    void 疾病用药术后适用性_代码层短路不调用知识或模型() {
        ChatEngine.Prepared p = engine.prepare("我有高血压刚做完手术，现在能做抗衰吗", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("主诊医生许可"));
        assertTrue(p.agentAnswer().contains("专业人员"));
        assertNull(p.prompt());
        verify(knowledgeService, never()).hasKnowledge();
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 城市有门店但项目未关联时明确说明没有支持该项目的门店() {
        when(storeService.resolveSpokenLocation("成都眼袋门店", "眼袋"))
                .thenReturn(new StoreService.LocationResolution(
                        StoreService.LocationStatus.UNAVAILABLE, "成都", null,
                        List.of(), List.of(), 0));

        ChatEngine.Prepared p = engine.prepare("成都眼袋门店", null, "user1");

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("明确支持“眼袋”的门店"));
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 未说明项目同时询问红肿和次日上班_先回答安全边界再邀请补充项目() {
        ChatEngine.Prepared p = engine.prepare("做完会红肿吗？明天能上班吗？", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("不能保证做完完全不红不肿"));
        assertTrue(p.agentAnswer().contains("取决于具体项目及个人反应"));
        assertTrue(p.agentAnswer().contains("明显、持续或加重"));
        assertTrue(p.agentAnswer().contains("具体项目"));
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 预约加价格多意图_先回答价格且本轮不启动预约动作() {
        when(businessFactService.tryAnswer(eq("我想预约抗衰，多少钱？"), any(MessageUnderstanding.Result.class)))
                .thenReturn(new BusinessFactService.StructuredAnswer("抗衰单部位活动价298元。", "price.抗衰"));

        ChatEngine.Prepared p = engine.prepare("我想预约抗衰，多少钱？", null, "user1");

        assertEquals("FACT", p.replyType());
        assertTrue(p.directAnswer().contains("298元"));
        assertTrue(p.directAnswer().contains("目前还没有替您登记"));
        assertTrue(p.directAnswer().contains("确认预约"));
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 投诉加退款多意图_回答政策并说明网页渠道暂不转人工() {
        when(businessFactService.tryAnswer(eq("我要投诉，顺便问退款"), any(MessageUnderstanding.Result.class)))
                .thenReturn(new BusinessFactService.StructuredAnswer(
                        "退款需要按已发布规则和订单状态核实。", "policy.refund"));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AgentService.AgentResult("好的，我马上帮您转人工客服。", true));

        ChatEngine.Prepared p = engine.prepare("我要投诉，顺便问退款", null, "user1");

        assertEquals("AGENT", p.replyType());
        assertTrue(p.agentAnswer().contains("退款需要按已发布规则"));
        assertTrue(p.agentAnswer().contains("暂不支持转接人工"));
        verify(conversationService, never()).setStatus(anyString(), eq("need_human"));
        verify(agentService).tryHandle(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 预约收费问句_只回答费用政策不误启动预约() {
        when(businessFactService.tryAnswer(eq("预约要钱吗？"), any(MessageUnderstanding.Result.class)))
                .thenReturn(new BusinessFactService.StructuredAnswer(
                        "登记预约不会自动扣款。", "payment.appointment"));

        ChatEngine.Prepared p = engine.prepare("预约要钱吗？", null, "user1");

        assertEquals("FACT", p.replyType());
        assertEquals("登记预约不会自动扣款。", p.directAnswer());
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 保证类问题_明确不承诺且不调用模型() {
        ChatEngine.Prepared p = engine.prepare("你们保证淡斑永不反弹吗", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("不能承诺"));
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 模型输出含违规功效断言_后置校验替换为合规话术() {
        String guarded = engine.guardGeneratedAnswer("我们的效果立竿见影，而且永不反弹。");

        assertTrue(guarded.contains("不能承诺"));
        assertFalse(guarded.contains("立竿见影"));
        assertFalse(guarded.contains("永不反弹"));
    }

    @Test
    void 模型输出锁定名额或赠送承诺_后置校验改为政策核实() {
        String guarded = engine.guardGeneratedAnswer("8元订金锁定名额，还额外赠送接车服务。");

        assertTrue(guarded.contains("具体门店确认为准"));
        assertFalse(guarded.contains("锁定名额"));
        assertFalse(guarded.contains("赠送接车"));
    }

    @Test
    void 心脏病癌症支架和未成年人属于明确禁忌() {
        for (String question : List.of("我有心脏病能做抗衰吗", "做过心脏支架可以做吗", "癌症患者能做吗", "未成年人能做吗")) {
            ChatEngine.Prepared p = engine.prepare(question, null, "user1");
            assertEquals("SAFETY", p.replyType(), question);
            assertTrue(p.agentAnswer().contains("明确禁忌"), question);
        }
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 阿司匹林问题禁止指导自行停药() {
        ChatEngine.Prepared p = engine.prepare("我在吃阿司匹林，需要停药吗", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("不要为了做项目自行停药"));
        assertTrue(p.agentAnswer().contains("开药的医生"));
    }

    @Test
    void 泛化用药痛感疗程年龄和多年斑点问题使用确定性安全话术() {
        Map<String, String> cases = Map.of(
                "最近在吃其他药，能做吗", "开药的医生",
                "做抗衰痛吗？要打麻药吗", "不能在线承诺完全不痛",
                "要做几次才有效果", "不能在线保证一次见效",
                "70岁了做抗衰还有用吗", "不能只根据年龄判断",
                "什么年龄适合做眼袋", "不能只根据年龄判断",
                "有病史能操作吗", "具备相应资质的专业人员",
                "斑点很多年了还能去掉吗", "不能保证去掉",
                "效果不满意怎么办", "不能预先承诺处理结果");

        cases.forEach((question, expected) -> {
            ChatEngine.Prepared p = engine.prepare(question, null, "user1");
            assertEquals("SAFETY", p.replyType(), question);
            assertTrue(p.agentAnswer().contains(expected), question + " -> " + p.agentAnswer());
            assertNull(p.prompt(), question);
        });
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 历史危险话术经模型输出后仍会被合规兜底拦截() {
        List<String> dangerous = List.of(
                "一般情况下，正在吃药是不影响的。",
                "不会有隐形消费，不满意还可以退订金。",
                "不痛的，一般不用敷麻药。",
                "做一次就能看到效果，一般不太需要做第二次。",
                "70岁做抗衰是可以的。",
                "斑点不管长了多少年都可以做。",
                "我们会给您一个满意的处理方案。",
                "可以免费安排接车，还能报销60元车费。");

        for (String answer : dangerous) {
            String guarded = engine.guardGeneratedAnswer(answer);
            assertNotEquals(answer, guarded, answer);
        }
    }

    @Test
    void 胡子项目原理回答删除无伤害和零恢复承诺() {
        String guarded = engine.guardGeneratedAnswer(
                "胡子去除会长期抑制毛发生长。不伤皮肤，做完就能正常生活。");

        assertTrue(guarded.contains("长期抑制毛发生长"));
        assertTrue(guarded.contains("皮肤反应因人而异"));
        assertFalse(guarded.contains("不伤皮肤"));
        assertFalse(guarded.contains("做完就能正常生活"));
    }

    @Test
    void 近期注射项目回答约十五天间隔() {
        ChatEngine.Prepared p = engine.prepare("上周刚打了除皱针，能做仪器项目吗", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("约15天"));
    }

    @Test
    void 刚做完手术优先使用术后保守提示() {
        ChatEngine.Prepared p = engine.prepare("刚做完手术一个月能做淡斑吗", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("暂时不要自行安排项目"));
        assertTrue(p.agentAnswer().contains("主诊医生许可"));
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 危险知识切片不会进入Rag上下文() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(doc("阿司匹林不用停药，完全不需要打麻药。", 0.20)));

        ChatEngine.Prepared p = engine.prepare("这个项目有什么特别注意的吗", null, "user1");

        assertEquals("REFUSAL", p.replyType());
        assertNull(p.prompt());
    }

    @Test
    void 首次促销话术删除危险句但保留安全价格信息() {
        when(businessFactService.initialProjectPitch("淡斑"))
                .thenReturn(new BusinessFactService.StructuredAnswer(
                        "淡斑活动价198元。限时活动30个名额，活动结束恢复原价。不满意无条件退款。", "price.xlsx#淡斑.sale_copy"));
        when(imageService.matchInitialProjectAssets(eq("淡斑"), eq("conv-1"), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(List.of(Map.of("name", "效果图", "url", "/effect.jpg", "type", "effect")));
        when(agentService.decideProjectIntroduction(anyString(), anyString(), eq("淡斑"), anyString(),
                eq(true), eq(false), nullable(String.class)))
                .thenReturn(new AgentService.ProjectIntroDecision(true, ""));

        ChatEngine.Prepared p = engine.prepare("详细介绍一下淡斑活动", null, "user1");

        assertEquals("INTRO", p.replyType());
        assertTrue(p.directAnswer().contains("活动价198元"));
        assertFalse(p.directAnswer().contains("限时活动30个名额"));
        assertFalse(p.directAnswer().contains("无条件退款"));
        assertTrue(p.directAnswer().contains("以项目中心当前已发布信息及具体门店确认为准"));
    }

    @Test
    void 写诗请求确定性拒答不受知识命中影响() {
        ChatEngine.Prepared p = engine.prepare("帮我写一首关于抗衰的诗", null, "user1");

        assertEquals("REFUSAL", p.replyType());
        assertTrue(p.refused());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 天气问题确定性拒答不读取知识库旧答案() {
        ChatEngine.Prepared p = engine.prepare("今天天气怎么样啊", null, "user1");

        assertEquals("REFUSAL", p.replyType());
        assertTrue(p.refused());
        assertEquals("OUT_OF_SCOPE", p.trace().knowledgeStatus());
        assertEquals("超出业务服务范围", p.trace().routeReason());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 订金退款不要求先补项目名() {
        when(businessFactService.tryAnswer(eq("订金能退吗"), any()))
                .thenReturn(new BusinessFactService.StructuredAnswer("退款需要按订单状态核实，不能承诺无条件退款。", "policy.depositRefund"));

        ChatEngine.Prepared p = engine.prepare("订金能退吗", null, "user1");

        assertEquals("FACT", p.replyType());
        assertTrue(p.directAnswer().contains("不能承诺无条件退款"));
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 订金退款变体和名额多少在项目澄清前处理() {
        when(businessFactService.tryAnswer(eq("订金不满意能退吗"), any()))
                .thenReturn(new BusinessFactService.StructuredAnswer(
                        "订金退款按规则和订单状态核实，不能承诺无条件退款。", "policy.depositRefund"));
        when(businessFactService.tryAnswer(eq("现在活动有多少名额"), any()))
                .thenReturn(new BusinessFactService.StructuredAnswer(
                        "活动名额实时变化，需要客服确认。", "activity.availability"));

        ChatEngine.Prepared refund = engine.prepare("订金不满意能退吗", null, "user1");
        ChatEngine.Prepared availability = engine.prepare("现在活动有多少名额", null, "user1");

        assertEquals("FACT", refund.replyType());
        assertTrue(refund.directAnswer().contains("不能承诺无条件退款"));
        assertEquals("FACT", availability.replyType());
        assertTrue(availability.directAnswer().contains("客服确认"));
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 项目包含部位问题在首次销售介绍前回答() {
        when(businessFactService.tryAnswer(eq("抗衰全脸包含7个部位吗"), any()))
                .thenReturn(new BusinessFactService.StructuredAnswer(
                        "项目中心尚未发布具体包含部位，不能按旧资料推断。", "project-scope#抗衰全脸"));

        ChatEngine.Prepared p = engine.prepare("抗衰全脸包含7个部位吗", null, "user1");

        assertEquals("FACT", p.replyType());
        assertTrue(p.directAnswer().contains("不能按旧资料推断"));
        verify(businessFactService, never()).initialProjectPitch(anyString());
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 全局活动收费面诊与接送政策在项目澄清前处理() {
        Map<String, String> answers = Map.of(
                "会不会动钱", "登记预约不会自动扣款",
                "不预约直接到店能享受活动价吗", "活动条件以门店确认",
                "到店会不会有隐形消费", "请确认完整收费明细",
                "到店检测面诊收费吗", "面诊收费以门店确认",
                "到店有接送吗", "接送范围以门店确认");
        answers.forEach((question, answer) -> when(businessFactService.tryAnswer(eq(question), any()))
                .thenReturn(new BusinessFactService.StructuredAnswer(answer, "policy.test")));

        answers.forEach((question, answer) -> {
            ChatEngine.Prepared p = engine.prepare(question, null, "user1");
            assertEquals("FACT", p.replyType(), question);
            assertEquals(answer, p.directAnswer(), question);
        });
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 库外玻尿酸和面膜价格问题直接拒答() {
        for (String question : List.of("玻尿酸填充多少钱一支", "你们家面膜多少钱")) {
            ChatEngine.Prepared p = engine.prepare(question, null, "user1");
            assertEquals("REFUSAL", p.replyType(), question);
            assertTrue(p.refused(), question);
        }
        verify(knowledgeService, never()).hasKnowledge();
    }

    @Test
    void 谐音辱骂先降温不直接误转人工() {
        ChatEngine.Prepared p = engine.prepare("你们就是垃圾克星，撒比客服", null, "user1");

        assertEquals("SAFETY", p.replyType());
        assertTrue(p.agentAnswer().contains("生气"));
        verify(agentService, never()).tryHandle(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 首次介绍替换不限年限和当天提亮承诺且保留价格() {
        when(businessFactService.initialProjectPitch("淡斑"))
                .thenReturn(new BusinessFactService.StructuredAnswer(
                        "不论多少年限都是可以操作的，做完当天可以提亮肤色淡斑活动价198元。8元订金锁定名额，活动额外赠送接车服务。", "price.xlsx#淡斑.sale_copy"));
        when(imageService.matchInitialProjectAssets(eq("淡斑"), eq("conv-1"), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(List.of(Map.of("name", "效果图", "url", "/effect.jpg", "type", "effect")));
        when(agentService.decideProjectIntroduction(anyString(), anyString(), eq("淡斑"), anyString(),
                eq(true), eq(false), nullable(String.class)))
                .thenReturn(new AgentService.ProjectIntroDecision(true, ""));

        ChatEngine.Prepared p = engine.prepare("详细介绍一下淡斑活动", null, "user1");

        assertTrue(p.directAnswer().contains("活动价198元"));
        assertTrue(p.directAnswer().contains("结合斑点类型"));
        assertFalse(p.directAnswer().contains("不论多少年限"));
        assertFalse(p.directAnswer().contains("当天可以提亮"));
        assertFalse(p.directAnswer().contains("锁定名额"));
        assertFalse(p.directAnswer().contains("额外赠送"));
        assertTrue(p.directAnswer().contains("接车服务请按所在城市和具体门店确认"));
    }

    // ==================== 位置消息：就近门店 ====================

    @Test
    void 位置消息_直接返回最近门店不走检索() {
        Map<String, Object> store = Map.of(
                "name", "昆明官渡店", "address", "云南省昆明市官渡区星耀路99号",
                "lat", 24.98, "lng", 102.72, "distance_km", 1.5);
        when(storeService.findNearest(anyDouble(), anyDouble(), anyInt(), nullable(String.class)))
                .thenReturn(List.of(store));
        when(storeService.tencentAddressCard(store)).thenReturn(new LinkCard(
                "昆明官渡店", "云南省昆明市官渡区星耀路99号",
                "https://apis.map.qq.com/uri/v1/marker?marker=coord:24.98,102.72",
                "/assets/tencent-map-card.svg"));

        ChatEngine.Prepared p = engine.prepare("位置", null, "user1", 24.9, 102.8);

        assertEquals("AGENT", p.replyType());
        assertNotNull(p.agentAnswer());
        assertTrue(p.agentAnswer().contains("昆明官渡店"), "应返回最近门店");
        assertFalse(p.agentAnswer().contains("https://"));
        assertEquals(1, p.linkCards().size());
        assertTrue(p.linkCards().get(0).url().startsWith(
                "https://apis.map.qq.com/uri/v1/marker"));
        assertNull(p.prompt(), "位置消息不进检索和分类，省 token");
        verifyNoModelOrSearch(p);
    }

    private void verifyNoModelOrSearch(ChatEngine.Prepared p) {
        assertNull(p.prompt());
        assertNull(p.directAnswer());
        assertFalse(p.refused());
    }

    // ==================== 图片意图 ====================

    @Test
    void 图片意图_命中关键词_附带图片() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(doc("眼袋前后对比案例。", 0.30)));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        when(imageService.matchByRequest(anyString(), eq("眼袋"), eq("conv-1"), anyInt(), anyBoolean()))
                .thenReturn(List.of(Map.of(
                        "id", 1L, "name", "眼袋对比图", "url", "/uploads/images/1.jpg", "type", "effect")));

        ChatEngine.Prepared p = engine.prepare("给我看看眼袋的对比图", null, "user1");

        assertFalse(p.images().isEmpty(), "命中图片意图应附带图片");
        assertEquals("AI", p.replyType());
        assertTrue(p.prompt().contains("系统已经选中1张效果图"));
        assertTrue(p.prompt().contains("严禁说“没有图片/效果图”"));
    }

    @Test
    void 无图片意图_不带图片() {
        when(knowledgeService.hasKnowledge()).thenReturn(true);
        when(knowledgeService.search(anyString(), anyInt(), nullable(String.class), nullable(String.class)))
                .thenReturn(List.of(doc("淡斑一次即可见效果。", 0.30)));
        when(agentService.tryHandle(anyString(), anyString(), anyString(), anyString())).thenReturn(null);

        ChatEngine.Prepared p = engine.prepare("淡斑要做几次", null, "user1");

        assertTrue(p.images().isEmpty());
    }
}

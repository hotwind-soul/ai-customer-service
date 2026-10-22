package com.example.aics.knowledge;

import com.example.aics.chat.MessageUnderstanding;
import com.example.aics.config.ConfigService;
import com.example.aics.project.ProjectCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BusinessFactServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private ConfigService config;
    private BusinessFactService service;

    @BeforeEach
    void setUp() {
        service = new BusinessFactService(jdbc, config);
    }

    @Test
    void 预约是否动钱_明确说明不会自动扣款且不虚构免费政策() {
        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "预约会不会动钱", MessageUnderstanding.analyze("预约会不会动钱", ""));

        assertNotNull(answer);
        assertEquals("policy.bookingPayment", answer.factKey());
        assertTrue(answer.answer().contains("不会自动扣款"));
        assertTrue(answer.answer().contains("确认后再主动支付"));
        assertFalse(answer.answer().contains("免费"));
    }

    @Test
    void 历史价格表只读取数字并生成中性话术() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("price.xlsx"))).thenReturn(List.of(
                "### 淡斑\n- project_key：淡斑\n- activity_price：198\n- original_price：1980\n" +
                        "- deposit：8\n- brief_copy：淡斑活动价198元，8元订金可抵扣，到店再付190元。"));

        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "淡斑多少钱", MessageUnderstanding.analyze("淡斑多少钱", ""));

        assertNotNull(answer);
        assertEquals("淡斑活动价198元（原价1980元），8元订金可抵扣，到店再付190元。", answer.answer());
        assertEquals("price-archive#淡斑", answer.factKey());
    }

    @Test
    void briefCopy价格过期时按数值字段生成回复() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("price.xlsx"))).thenReturn(List.of(
                "### 淡斑\n- project_key：淡斑\n- activity_price：228\n- original_price：1980\n" +
                        "- deposit：8\n- brief_copy：淡斑活动价198元，8元订金可抵扣，到店再付190元。"));

        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "淡斑多少钱", MessageUnderstanding.analyze("淡斑多少钱", ""));

        assertNotNull(answer);
        assertTrue(answer.answer().contains("活动价228元"));
        assertTrue(answer.answer().contains("到店再付220元"));
        assertFalse(answer.answer().contains("活动价198元"));
    }

    @Test
    void briefCopy包含锁定名额时回退为中性订金抵扣话术() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("price.xlsx"))).thenReturn(List.of(
                "### 淡斑\n- project_key：淡斑\n- activity_price：198\n- original_price：1980\n" +
                        "- deposit：8\n- brief_copy：淡斑活动价198元，8元订金锁定名额，到店再付190元。"));

        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "淡斑多少钱", MessageUnderstanding.analyze("淡斑多少钱", ""));

        assertTrue(answer.answer().contains("8元订金可抵扣"));
        assertFalse(answer.answer().contains("锁定名额"));
    }

    @Test
    void 项目中心结构化报价优先于旧价格表() {
        ProjectCatalogService catalog = org.mockito.Mockito.mock(ProjectCatalogService.class);
        when(catalog.publishedOffer("淡斑")).thenReturn(new ProjectCatalogService.Offer(
                new BigDecimal("228"), new BigDecimal("1980"), new BigDecimal("8"),
                "淡斑活动价228元，8元订金可抵扣，到店再付220元。", null,
                "project-center"));
        BusinessFactService catalogService = new BusinessFactService(jdbc, config, catalog);

        BusinessFactService.StructuredAnswer answer = catalogService.tryAnswer(
                "淡斑多少钱", MessageUnderstanding.analyze("淡斑多少钱", ""));

        assertNotNull(answer);
        assertEquals("淡斑活动价228元，8元订金可抵扣，到店再付220元。", answer.answer());
        assertEquals("project-offer#淡斑", answer.factKey());
        verifyNoInteractions(jdbc);
    }

    @Test
    void 络腮胡价格会合并已发布同价事实并完整回答两问() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("price.xlsx"))).thenReturn(List.of(
                "### 胡子\n- project_key：胡子\n- activity_price：280\n- original_price：980\n" +
                        "- deposit：8\n- brief_copy：胡子活动价280元（原价980元），8元订金可抵扣，到店再付272元。"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("price.hair.beardSurcharge")))
                .thenReturn(List.of("络腮胡按同一活动价，不另外加钱。"));

        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "脱胡子多少钱？络腮胡要加钱吗？",
                MessageUnderstanding.analyze("脱胡子多少钱？络腮胡要加钱吗？", ""));

        assertNotNull(answer);
        assertTrue(answer.answer().contains("活动价280元"));
        assertTrue(answer.answer().contains("络腮胡按同一活动价，不另外加钱"));
        assertEquals("price-archive#胡子+price.hair.beardSurcharge", answer.factKey());
    }

    @Test
    void 首次项目话术不读取历史saleCopy并只生成中性数字兜底() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("price.xlsx"))).thenReturn(List.of(
                "### 淡斑\n- project_key：淡斑\n- activity_price：198\n- original_price：1980\n- deposit：8\n" +
                        "- sale_copy：限30个名额，赠送接车，不满意无条件退款。\n" +
                        "- brief_copy：淡斑活动价198元。"));

        BusinessFactService.StructuredAnswer answer = service.initialProjectPitch("淡斑");

        assertNotNull(answer);
        assertTrue(answer.answer().contains("活动价198元"));
        assertTrue(answer.answer().contains("订金可抵扣"));
        assertFalse(answer.answer().contains("限30个名额"));
        assertFalse(answer.answer().contains("赠送接车"));
        assertFalse(answer.answer().contains("无条件退款"));
        assertEquals("price-archive#淡斑", answer.factKey());
    }

    @Test
    void 变体退款和名额问法直接返回保守政策() {
        BusinessFactService.StructuredAnswer refund = service.tryAnswer(
                "订金不满意能退吗", MessageUnderstanding.analyze("订金不满意能退吗", ""));
        BusinessFactService.StructuredAnswer availability = service.tryAnswer(
                "现在活动有多少名额", MessageUnderstanding.analyze("现在活动有多少名额", ""));

        assertEquals("policy.depositRefund", refund.factKey());
        assertTrue(refund.answer().contains("不能承诺无条件退款"));
        assertEquals("activity.availability", availability.factKey());
        assertTrue(availability.answer().contains("客服按最新情况确认"));
    }

    @Test
    void 项目部位只读取项目中心服务范围且缺失时不推断旧口径() {
        ProjectCatalogService catalog = org.mockito.Mockito.mock(ProjectCatalogService.class);
        when(catalog.canonicalName("抗衰全脸有几个部位")).thenReturn("抗衰全脸");
        BusinessFactService catalogService = new BusinessFactService(jdbc, config, catalog);

        BusinessFactService.StructuredAnswer missing = catalogService.tryAnswer(
                "抗衰全脸有几个部位", MessageUnderstanding.analyze("抗衰全脸有几个部位", ""));

        assertEquals("project-scope#抗衰全脸", missing.factKey());
        assertTrue(missing.answer().contains("没有发布"));
        assertTrue(missing.answer().contains("不能按旧资料推断"));
        assertFalse(missing.answer().contains("7个部位"));
    }

    @Test
    void 门店营业时间使用独立配置() {
        when(config.get(eq("store.businessHours"), anyString())).thenReturn("10:00-18:00");

        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "门店几点营业", MessageUnderstanding.analyze("门店几点营业", ""));

        assertNotNull(answer);
        assertTrue(answer.answer().contains("10:00-18:00"));
        assertEquals("hours.store.businessHours", answer.factKey());
    }

    @Test
    void 客服在线时间使用独立配置且不会回答门店时间() {
        when(config.get(eq("agent.workHours"), anyString())).thenReturn("09:00-22:00");

        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "客服几点上班", MessageUnderstanding.analyze("客服几点上班", ""));

        assertNotNull(answer);
        assertTrue(answer.answer().contains("09:00-22:00"));
        assertFalse(answer.answer().contains("10:00-18:00"));
        assertEquals("hours.agent.workHours", answer.factKey());
    }

    @Test
    void 同时询问门店和客服时间会一次完整回答() {
        when(config.get(eq("store.businessHours"), anyString())).thenReturn("10:00-18:00");
        when(config.get(eq("agent.workHours"), anyString())).thenReturn("09:00-22:00");

        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "门店几点营业，人工客服几点在线", MessageUnderstanding.analyze(
                        "门店几点营业，人工客服几点在线", ""));

        assertNotNull(answer);
        assertTrue(answer.answer().contains("门店营业时间是每天10:00-18:00"));
        assertTrue(answer.answer().contains("人工客服在线时间是每天09:00-22:00"));
        assertEquals("hours.storeAndAgent", answer.factKey());
    }

    @Test
    void 单独询问人工客服营业时间不会附带门店时间() {
        when(config.get(eq("agent.workHours"), anyString())).thenReturn("09:00-22:00");

        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "人工客服营业时间", MessageUnderstanding.analyze("人工客服营业时间", ""));

        assertNotNull(answer);
        assertTrue(answer.answer().contains("09:00-22:00"));
        assertFalse(answer.answer().contains("10:00-18:00"));
        assertEquals("hours.agent.workHours", answer.factKey());
    }

    @Test
    void 男士咨询抗衰淡斑直接回答项目不限女性() {
        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "男的能做抗衰和淡斑吗", MessageUnderstanding.analyze("男的能做抗衰和淡斑吗", ""));

        assertNotNull(answer);
        assertTrue(answer.answer().contains("男士也可以咨询"));
        assertTrue(answer.answer().contains("面诊评估"));
    }

    @Test
    void 订金退款支付方式和发票使用保守政策回答() {
        BusinessFactService.StructuredAnswer refund = service.tryAnswer(
                "订金能退吗", MessageUnderstanding.analyze("订金能退吗", ""));
        BusinessFactService.StructuredAnswer payment = service.tryAnswer(
                "可以刷卡吗", MessageUnderstanding.analyze("可以刷卡吗", ""));
        BusinessFactService.StructuredAnswer invoice = service.tryAnswer(
                "能开发票吗", MessageUnderstanding.analyze("能开发票吗", ""));

        assertTrue(refund.answer().contains("不能承诺无条件退款"));
        assertTrue(payment.answer().contains("具体门店当期实际支持"));
        assertTrue(invoice.answer().contains("实际消费情况确认"));
    }

    @Test
    void 泛化退款问题也返回保守政策供投诉多意图复用() {
        BusinessFactService.StructuredAnswer answer = service.tryAnswer(
                "我要投诉，顺便问退款", MessageUnderstanding.analyze("我要投诉，顺便问退款", ""));

        assertNotNull(answer);
        assertEquals("policy.refund", answer.factKey());
        assertTrue(answer.answer().contains("实际订单状态"));
        assertTrue(answer.answer().contains("不能在线承诺无条件退款"));
    }

    @Test
    void 全局活动收费面诊与接送问题不要求项目名() {
        BusinessFactService.StructuredAnswer booking = service.tryAnswer(
                "不预约直接到店能享受活动价吗", MessageUnderstanding.analyze(
                        "不预约直接到店能享受活动价吗", ""));
        BusinessFactService.StructuredAnswer charges = service.tryAnswer(
                "到店会不会有隐形消费", MessageUnderstanding.analyze(
                        "到店会不会有隐形消费", ""));
        BusinessFactService.StructuredAnswer consultation = service.tryAnswer(
                "到店检测面诊收费吗", MessageUnderstanding.analyze(
                        "到店检测面诊收费吗", ""));
        BusinessFactService.StructuredAnswer pickup = service.tryAnswer(
                "到店有接送吗", MessageUnderstanding.analyze("到店有接送吗", ""));

        assertTrue(booking.answer().contains("具体门店确认为准"));
        assertTrue(charges.answer().contains("完整收费明细"));
        assertTrue(charges.answer().contains("不能承诺订金无条件可退"));
        assertTrue(consultation.answer().contains("具体门店确认为准"));
        assertTrue(pickup.answer().contains("接车范围和车费标准"));
    }

    @Test
    void 停车排队和送回服务不再引用旧FAQ承诺() {
        BusinessFactService.StructuredAnswer parking = service.tryAnswer(
                "门店有停车位吗", MessageUnderstanding.analyze("门店有停车位吗", ""));
        BusinessFactService.StructuredAnswer queue = service.tryAnswer(
                "预约后到店不用排队吗", MessageUnderstanding.analyze("预约后到店不用排队吗", ""));
        BusinessFactService.StructuredAnswer returnRide = service.tryAnswer(
                "接车服务能送回吗", MessageUnderstanding.analyze("接车服务能送回吗", ""));

        assertTrue(parking.answer().contains("不能统一承诺一定有车位"));
        assertTrue(queue.answer().contains("不能保证到店完全不用等待"));
        assertTrue(returnRide.answer().contains("未明确接车服务是否包含返程送回"));
        assertTrue(returnRide.answer().contains("不能承诺往返接送"));
    }

    @Test
    void 项目列表实时读取项目中心主项目() {
        ProjectCatalogService catalog = org.mockito.Mockito.mock(ProjectCatalogService.class);
        when(catalog.publishedNames()).thenReturn(List.of("抗衰", "眼袋", "淡斑", "胡子"));
        BusinessFactService catalogService = new BusinessFactService(jdbc, config, catalog);

        BusinessFactService.StructuredAnswer answer = catalogService.tryAnswer(
                "你们都做什么项目", MessageUnderstanding.analyze("你们都做什么项目", ""));

        assertEquals("project.currentCatalog", answer.factKey());
        assertTrue(answer.answer().contains("抗衰、眼袋、淡斑、胡子"));
    }
}

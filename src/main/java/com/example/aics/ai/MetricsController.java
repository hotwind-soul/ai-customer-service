package com.example.aics.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模型网关指标接口：调用次数、降级次数、最近降级时间。
 * 受管理鉴权保护（见 WebConfig，/api/metrics 已加入 adminAuthInterceptor）。
 * 运营用途：看模型健康度与成本量级，出现降级（failover_count 增长）时优先排查当前主模型。
 */
@RestController
public class MetricsController {

    private final ModelGateway modelGateway;

    public MetricsController(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() {
        return modelGateway.metrics();
    }
}

package com.example.aics.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 显式维护模式：使用当前已配置的向量模型重建全部有效客户知识。
 * 默认关闭，只能由本地启动参数 --aics.maintenance.reindex-on-start=true 开启；
 * 不暴露匿名 HTTP 接口，也不绕过后台管理员认证。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "aics.maintenance", name = "reindex-on-start", havingValue = "true")
public class KnowledgeReindexStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReindexStartupRunner.class);
    private final KnowledgeService knowledgeService;

    public KnowledgeReindexStartupRunner(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("维护模式：开始重建有效知识向量");
        int count = knowledgeService.reindexAll();
        log.info("维护模式：有效知识向量重建完成，共 {} 片", count);
    }
}

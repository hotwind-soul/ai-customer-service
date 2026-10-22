-- PostgreSQL 官方初始化阶段只负责启用实例级扩展。
-- 所有表、索引和数据迁移统一由应用内 Flyway 的 db/migration/V* 管理。
CREATE EXTENSION IF NOT EXISTS vector;

-- 固化数据库级时区为北京时间（Asia/Shanghai）。
-- 容器 TZ 环境变量虽能驱动 PG 默认时区，但属隐式依赖；此处显式 ALTER 为硬保证，
-- 防止换镜像/宿主机时区变动时，按日聚合（current_date / ::date）的报表趋势图错位 ±1 天。
-- 注意：仅对“新建库”生效；已运行的库请在 aics 库连接下执行同一条 ALTER DATABASE 语句（幂等）。
ALTER DATABASE aics SET timezone TO 'Asia/Shanghai';

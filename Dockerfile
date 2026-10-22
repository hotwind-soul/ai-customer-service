# 第 12 周：应用打包镜像
# 构建：docker build -t aics-app .
# （先在本地执行 mvn -DskipTests package 打出 jar，或用多阶段构建见下）

# 多阶段构建：第一阶段用 Maven 编译，第二阶段只留运行时
FROM maven:3.9-eclipse-temurin-17 AS build
# 2G 内存服务器防 OOM：限制 Maven/JVM 编译堆，避免把物理内存吃爆导致构建被 kill。
ENV MAVEN_OPTS="-Xmx768m -XX:MaxMetaspaceSize=256m"
WORKDIR /build
COPY pom.xml .
COPY settings.xml .
COPY src ./src
# 用阿里云 Maven 镜像（国内可达），避免服务器直连 Maven Central 被墙导致构建失败。
RUN mvn -DskipTests -s settings.xml package

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/ai-customer-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
# 图片上传目录（容器里 /app/uploads，用卷持久化）
VOLUME /app/uploads
ENTRYPOINT ["java", "-jar", "app.jar"]

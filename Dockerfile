# ============================================================
# agent-service 生产镜像（多阶段构建，最终镜像仅含 JRE）
# ============================================================
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build

# 先复制 pom 单独拉依赖，利用 Docker 层缓存加速后续构建
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ============================================================
FROM eclipse-temurin:17-jre-jammy AS runtime

# 时区与中文环境
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 非 root 运行（安全加固）
RUN groupadd -r appuser && useradd -r -g appuser appuser

WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar

RUN chown -R appuser:appuser /app
USER appuser

# 容器内存感知：让 JVM 按 cgroup 限制自动算堆大小
# MaxRAMPercentage=70 避免堆占满容器内存被 OOM Killer 杀掉
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

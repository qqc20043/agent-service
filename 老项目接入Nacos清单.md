# 老项目接入 Nacos 的最小改动清单

> 目标：让 `tearSpring` 注册进 Nacos，被 `agent-service` 通过 OpenFeign 发现。
> 原则：**业务代码零改动**，只加依赖 + 加配置。

---

## 一、加依赖（`tearSpring/pom.xml`）

⚠️ **版本线必须匹配老项目的 Boot 2.6.13**，不能用 agent-service 的 2023.0.1.0：

| 组件 | 版本 |
|---|---|
| Spring Boot | 2.6.13（不变） |
| Spring Cloud | **2021.0.5** |
| Spring Cloud Alibaba | **2021.0.5.0** |

```xml
<properties>
    <java.version>1.8</java.version>
    <spring-boot.version>2.6.13</spring-boot.version>
    <spring-cloud.version>2021.0.5</spring-cloud.version>
    <spring-cloud-alibaba.version>2021.0.5.0</spring-cloud-alibaba.version>
</properties>

<dependencies>
    <!-- 原有依赖保持不变 -->

    <!-- 新增：Nacos 服务注册 -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>

    <!-- 新增：Actuator，Docker HEALTHCHECK 需要 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
</dependencies>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring-boot.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <!-- 新增两条 -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>${spring-cloud-alibaba.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 二、加配置（`tearSpring/src/main/resources/application.yaml`）

在现有配置基础上**追加**：

```yaml
spring:
  application:
    name: tearSpring          # ← 必须与 agent-service 里
                              #   services.tear-spring.service-name 一致
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        namespace: public
        group: DEFAULT_GROUP

# Docker HEALTHCHECK 依赖
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always

server:
  port: 8080
```

---

## 三、容器内访问数据库

老项目 `application.yaml` 里现在写死：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/db_tear
```

**容器里 localhost 指向容器自己**，必须改成可用环境变量覆盖的形式：

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/db_tear?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:root}
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:127.0.0.1}
      port: 6379
      database: 1
      password: ${REDIS_PASSWORD:}
```

> 注意：Boot 2.6 里 Redis 配置是 `spring.redis.*`，
> 但 2.6 已开始支持 `spring.data.redis.*`。**保持你原来的写法即可**，
> 只要保证 host 能从环境变量覆盖。

---

## 四、CORS 调整

老项目所有 Controller 都有 `@CrossOrigin`，生产环境建议收紧：

```java
// 原来（任何域名都可访问，不安全）
@CrossOrigin

// 改成
@CrossOrigin(origins = {"https://your-domain.com", "https://servicewechat.com"})
```

`servicewechat.com` 是微信小程序的来源域名，不加会导致小程序请求被拒。

> 生产环境实际上因为同源（都走 Nginx 同一个域名）不会有跨域问题，
> 但保留配置以防小程序 WebView 场景。

---

## 五、构建与推送镜像

```bash
cd tearSpring

# 构建
docker build -t mansicha/tear-spring:latest .

# 推送到镜像仓库（可选，多机部署时需要）
docker tag mansicha/tear-spring:latest your-registry/mansicha/tear-spring:latest
docker push your-registry/mansicha/tear-spring:latest

# 单机部署可直接用本地镜像，docker-compose.prod.yml 里
# tear-spring 服务的 image 字段已指向 mansicha/tear-spring:latest
```

---

## 六、验证

```bash
# 1. 启动后看 Nacos 控制台，应有 tearSpring 与 agent-service 两个服务
#    http://your-server:8848/nacos  （需 SSH 隧道，8848 不对外）

# 2. 从 agent-service 容器内测试能否解析到老项目
docker exec ms-agent-service sh -c \
  'wget -qO- http://tear-spring:8080/actuator/health'

# 3. 走 Nginx 测试老项目接口
curl https://api.your-domain.com/category/lists
```

---

## 七、常见坑

| 现象 | 原因 | 解法 |
|---|---|---|
| Nacos 里看不到 tearSpring | `spring.application.name` 没配 | 补上 |
| Agent 调用返回 401 | 老项目拦截器要求 token | Agent 侧 FeignAuthConfig 已透传，确认前端带了 token |
| 调用超时 | Nacos 注册的是容器内 IP，跨网络不通 | 确保两个服务在同一 Docker network |
| 启动报 `NoSuchMethodError` | Spring Cloud 版本与 Boot 不匹配 | 严格用 2021.0.5 / 2021.0.5.0 |
| Redis 连不上 | 老项目用 database 1，Agent 用 2 | 正常，两个库独立 |

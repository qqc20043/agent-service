# agent-service 骨架说明

这是「漫思茶」国奖项目升级为 **Java 后端 / Agent 方向** 的核心产出。
所有 8 项你要的技术都在这里有真实落点，不是罗列名词。

---

## 一、目录结构

```
agent骨架/
├── pom.xml                     Boot 3.2.4 + JDK17 + 全量依赖
├── docker-compose.yml          Nacos/RabbitMQ/Redis/Qdrant/Milvus 一键起
├── sql/
│   └── db_agent.sql            Agent 侧 7 张表 + 演示数据
└── src/main/
    ├── resources/
    │   └── application.yaml    Nacos 配置中心接入 + 全部参数
    └── java/com/guchen/agent/
        ├── AgentServiceApplication.java     @EnableDiscoveryClient/@EnableFeignClients
        ├── llm/
        │   └── LlmClient.java               手写 LLM 客户端：SSE/重试/Function Calling/token 计量
        ├── agent/
        │   ├── AgentOrchestrator.java       ReAct 循环 + 意图路由 + 写操作硬上限
        │   └── AgentGraphConfig.java        ★ LangGraph4j DAG 编排（并行 + 条件边）
        ├── controller/
        │   └── AgentChatController.java     ★ SSE 流式输出
        ├── tool/
        │   └── ToolRegistry.java            20+ 工具注册 + schema 动态裁剪
        ├── client/
        │   ├── TearSpringClient.java        ★ OpenFeign 调老项目 54 接口
        │   ├── FeignAuthConfig.java         token 透传（老项目 Redis 二次校验要求）
        │   └── TearSpringClientFallback.java 降级
        ├── mq/
        │   ├── RabbitMqConfig.java          ★ RabbitMQ + 死信队列
        │   ├── OrderEventProducer.java      订单事件生产
        │   └── OrderEventConsumer.java      手动 ack + Redis 幂等 + DLQ
        ├── vector/
        │   ├── VectorStore.java             向量库抽象（对比实验基础）
        │   ├── QdrantVectorStore.java       ★ Qdrant 主链路
        │   └── MilvusVectorStore.java       ★ Milvus 对比基准
        ├── rag/
        │   └── HybridRetriever.java         ★ BM25 + 向量 + RRF + Rerank
        ├── mcp/
        │   └── McpServerController.java     ★ MCP Server（JSON-RPC 2.0）
        ├── a2a/
        │   └── A2aController.java           ★ A2A（Agent Card + tasks/send）
        ├── memory/
        │   └── SessionMemoryService.java    Redis 会话记忆 + 摘要压缩
        └── trace/
            └── AgentTraceService.java       链路追踪 + 成本计量 + 幂等键
```

★ = 简历里直接对应一条 bullet 的代码

---

## 二、快速启动

### 1. 环境（详见 [环境准备-JDK17与Docker.md](<../环境准备-JDK17与Docker.md>)）

```powershell
# 确认 JDK17
& "D:\Java\jdk-17\bin\java.exe" -version

# 起中间件
cd "D:\work\工作学习\agent骨架"
docker compose up -d nacos rabbitmq redis qdrant
docker compose ps
```

### 2. 建库

```powershell
& "D:\MySql 8.0\bin\mysql.exe" -uroot -proot < "sql\db_agent.sql"
```

### 3. 让老项目注册进 Nacos

老项目 `application.yaml` 加两行（**业务代码零改动**）：

```yaml
spring:
  application:
    name: tearSpring
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
```

并在 `tearSpring/pom.xml` 加：

```xml
<dependency>
  <groupId>com.alibaba.cloud</groupId>
  <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```

> ⚠️ 老项目是 Boot 2.6.13，要用 **Spring Cloud Alibaba 2021.0.5.0** 这条线，
> 不是 agent-service 用的 2023.0.1.0。两者各自独立，互不影响。

### 4. 启动 agent-service

```powershell
$env:JAVA_HOME = "D:\Java\jdk-17"
& "D:\maven-3.9.6\bin\mvn.cmd" clean spring-boot:run
```

### 5. 验证每一项技术

```powershell
# ① Nacos 注册 —— 控制台应看到 agent-service 与 tearSpring 两个服务
start http://localhost:8848/nacos

# ② SSE 流式
curl -N -H "token: YOUR_TOKEN" -H "Content-Type: application/json" `
     -d '{\"message\":\"有哪些绿茶推荐\",\"sessionId\":\"s1\"}' `
     http://localhost:8081/api/agent/chat/stream

# ③ MCP 工具列表
curl -X POST http://localhost:8081/mcp -H "Content-Type: application/json" `
     -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}'

# ④ MCP 调用工具
curl -X POST http://localhost:8081/mcp -H "Content-Type: application/json" `
     -d '{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_categories\",\"arguments\":{}}}'

# ⑤ A2A Agent Card
curl http://localhost:8081/.well-known/agent.json

# ⑥ A2A 委托任务
curl -X POST http://localhost:8081/a2a -H "Content-Type: application/json" `
     -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/send\",\"params\":{\"id\":\"t1\",\"message\":{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"龙井和碧螺春区别\"}]}}}'

# ⑦ RabbitMQ 管理台 —— 能看到 ms.order.exchange 与两个队列
start http://localhost:15672

# ⑧ Qdrant Dashboard
start http://localhost:6333/dashboard

# ⑨ 成本与链路（跑几轮对话后查）
& "D:\MySql 8.0\bin\mysql.exe" -uroot -proot -e `
  "SELECT trace_id, span_name, prompt_tokens, completion_tokens, cost_yuan, latency_ms FROM db_agent.agent_trace ORDER BY id DESC LIMIT 10;"
```

---

## 三、TODO 清单（你需要亲手补的部分）

代码骨架已就位，以下标了 `TODO` 的地方需要接真实服务：

| 文件 | TODO | 怎么补 |
|---|---|---|
| `LlmClient` | 无需改 | 填 `LLM_API_KEY` 即可 |
| `HybridRetriever#embed` | 接入 embedding 服务 | 调 BGE-M3 或 OpenAI `/v1/embeddings` |
| `HybridRetriever#rerank` | 接入 Rerank | 调 bge-reranker HTTP 服务 |
| `TearSpringClient#searchProduct` | 老项目加搜索接口 | 或在 Agent 侧本地过滤 |
| `AgentGraphConfig#retrieveStub` | 换成 `HybridRetriever` | 注入即可 |
| `OrderEventConsumer` | 接入 AgentOrchestrator | 生成搭配建议 |
| `A2aController#handleSend` | 接入 AgentOrchestrator | 返回真实回答 |
| `AgentTraceService` | 已完整 | 建表后即可用 |

---

## 四、简历 bullet 对照表

每写完一项，就把对应 bullet 的 ⏳ 改成 ✅：

| 技术 | 简历可写 |
|---|---|
| Nacos | 基于 Nacos 实现服务注册发现与配置中心，Agent 运行参数（最大迭代轮次、模型名）支持热更新不重启 |
| OpenFeign | 通过 OpenFeign + LoadBalancer 声明式调用存量电商服务 54 个接口，配合 FallbackFactory 实现服务降级 |
| RabbitMQ | 下单事件经 RabbitMQ 异步触发 Agent 任务，手动 ack + 死信队列 + Redis 幂等键保证消息不丢不重 |
| LangGraph4j | 用 LangGraph4j 的 StateGraph 编排茶礼推荐 DAG，双分支并行检索 + 条件边实现受控重试 |
| Qdrant | 主向量库，payload 过滤实现按茶类隔离检索 |
| Milvus | 对比基准，同数据集下产出 Qdrant vs Milvus 的 Recall@5 / P99 延迟对比报告 |
| MCP | 实现 MCP Server，将 20+ 业务接口标准化暴露，任意 MCP 客户端可接入 |
| A2A | 实现 A2A Agent Card 与 tasks/send，支持将茶知识问答子任务委托给专用 Agent |
| SSE | 基于 SseEmitter 实现 token 级流式输出，含心跳保活与客户端断连处理 |
| DAG | 确定性主干 + 局部自主决策，写操作加硬上限，规避全自主 Agent 的不可控风险 |

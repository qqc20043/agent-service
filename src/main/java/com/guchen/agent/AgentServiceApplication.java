package com.guchen.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 漫思茶 Agent 服务入口。
 *
 * 技术栈落点：
 *  - @EnableDiscoveryClient  → Nacos 服务注册
 *  - @EnableFeignClients     → 声明式调用老项目 tearSpring 的 54 个接口
 *  - @EnableAsync            → RabbitMQ 消费者与 SSE 推送异步化
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.guchen.agent.client")
@EnableAsync
@MapperScan("com.guchen.agent.mapper")
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}

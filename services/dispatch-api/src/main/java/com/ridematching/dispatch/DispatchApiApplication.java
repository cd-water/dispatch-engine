package com.ridematching.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 叫车请求入口：REST API、幂等、交接给 Kafka。
 *
 * <p>运行于虚拟线程（ADR-0006）：对 Redis、PostgreSQL 和 Kafka 的阻塞 I/O 被写成直线式
 * 代码，等待期间 JVM 会把载体线程卸载下来。
 */
@SpringBootApplication
public class DispatchApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatchApiApplication.class, args);
    }
}

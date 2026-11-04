package com.ridematching.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 通过 WebSocket 摄取 GPS 到 Redis 地理空间索引。
 *
 * <p>运行于虚拟线程（ADR-0006）：对 Redis、PostgreSQL 和 Kafka 的阻塞 I/O 被写成直线式
 * 代码，等待期间 JVM 会把载体线程卸载下来。
 */
@SpringBootApplication
public class LocationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocationServiceApplication.class, args);
    }
}

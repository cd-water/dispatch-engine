package com.ridematching.location.application;

import com.ridematching.geoindex.DriverLocationIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

/**
 * 驱逐那些连接已死却没发出关闭帧的司机。
 *
 * <p>半开套接字会留下"幽灵司机"：仍在地理空间索引里，仍会被邻近搜索返回，却注定永远不会
 * 应答报价。每个幽灵都浪费一个候选名额和一次报价 TTL，所以随着它们积累，匹配延迟会悄悄
 * 恶化。
 *
 * <p>仅靠 TCP 不会暴露这个问题——从服务器角度看，一个没有流量的套接字可以永远开着。改用
 * 最后在线时间的有序集合作为事实依据。
 */
public class StaleDriverReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleDriverReaper.class);

    private final DriverLocationIndex index;
    private final LocationIngestProperties properties;
    private final Clock clock;

    public StaleDriverReaper(DriverLocationIndex index,
                             LocationIngestProperties properties,
                             Clock clock) {
        this.index = index;
        this.properties = properties;
        this.clock = clock;
    }

    /** 清扫一次。返回驱逐了多少位司机。 */
    public int reap() {
        Instant cutoff = clock.instant().minus(properties.getStaleAfter());
        try {
            int evicted = index.evictStaleOlderThan(cutoff);
            if (evicted > 0) {
                log.info("Evicted {} stale drivers last seen before {}", evicted, cutoff);
            }
            return evicted;
        } catch (RuntimeException e) {
            // 某些执行器会悄悄取消抛异常的计划任务，那会让幽灵驱逐在整个进程生命周期内
            // 失效。吞掉异常并报告；下一轮清扫会再试。
            log.warn("Stale driver sweep failed: {}", e.getMessage());
            return 0;
        }
    }
}

package com.ridematching.geoindex;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.driver.NearbyDriver;
import com.ridematching.domain.geo.Coordinates;

import java.time.Instant;
import java.util.List;

/**
 * 在线司机位置的地理空间索引——一个出站端口（ADR-0001）。
 *
 * <p>Redis 是系统随附的实现（ADR-0002），但这个接口之上的任何东西都不知道这一点。正是这个
 * 接口让路线图上的 PostGIS 对比基准成为可能：第二个适配器，同样的测试，同样的负载。
 */
public interface DriverLocationIndex {

    /**
     * 写入一批位置。
     *
     * <p>用批量而不是逐条写入，因为目标是每秒 10,000 次更新。每一帧一次往返会把整个延迟
     * 预算都耗在网络开销上；每批一次流水线调用把每秒数千次往返降到几十次。
     */
    void upsertAll(List<DriverLocation> locations);

    /** 将司机登记为已连接且可派单。 */
    void markOnline(DriverId driverId);

    /**
     * 将司机从索引中彻底移除。
     *
     * <p>用移除而不是状态标志：一个已断连的司机根本不应被邻近搜索返回，事后过滤只会浪费
     * 候选名额。
     */
    void markOffline(DriverId driverId);

    /** {@code radiusMeters} 范围内的候选者，由近及远，最多 {@code limit} 个。 */
    List<NearbyDriver> search(Coordinates center, double radiusMeters, int limit);

    /**
     * 清除最后一次上报早于 {@code cutoff} 的司机，返回清除了多少个。
     *
     * <p>覆盖半开连接的情况：一个没发送关闭帧就死掉的 socket 会留下一个"幽灵司机"，否则它
     * 会被匹配到却永远不应答。
     */
    int evictStaleOlderThan(Instant cutoff);

    /** 当前索引中的司机数量。以 gauge 形式暴露。 */
    long size();
}

package com.ridematching.matching.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 匹配调优。每个值都是乘客等待时间与匹配质量之间的权衡，所以它们全都是负载测试可以扫描的
 * 配置，而不是代码中的常量。
 */
@ConfigurationProperties(prefix = "ridematching.matching")
public class MatchingProperties {

    /**
     * 按顺序尝试的扩展搜索半径，单位米。
     *
     * <p>从窄开始让常见情况便宜且上车点更近；只在无可认领司机时才扩大，避免在稍远处明明
     * 有司机的情况下告诉乘客没有司机。
     */
    private List<Double> searchRadiiMeters = List.of(2_000.0, 3_200.0, 5_000.0);

    /** 每个半径取回的候选数。有上限，因为每一个都可能花费一次认领往返。 */
    private int candidateLimit = 30;

    /**
     * 认领在 Redis 让它过期之前保持多久。
     *
     * <p>刻意长于报价 TTL：认领必须比报价活得久，这样司机就不会在报价过期与引擎察觉之间的
     * 间隙里被再次认领。
     */
    private Duration claimTtl = Duration.ofSeconds(10);

    /** 司机在多长时间内必须接受，否则报价失效。 */
    private Duration offerTtl = Duration.ofSeconds(8);

    /** 总预算：超过此时间后，请求被报告为 UNMATCHED。 */
    private Duration matchingSla = Duration.ofSeconds(30);

    public List<Double> getSearchRadiiMeters() {
        return searchRadiiMeters;
    }

    public void setSearchRadiiMeters(List<Double> searchRadiiMeters) {
        this.searchRadiiMeters = searchRadiiMeters;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public Duration getClaimTtl() {
        return claimTtl;
    }

    public void setClaimTtl(Duration claimTtl) {
        this.claimTtl = claimTtl;
    }

    public Duration getOfferTtl() {
        return offerTtl;
    }

    public void setOfferTtl(Duration offerTtl) {
        this.offerTtl = offerTtl;
    }

    public Duration getMatchingSla() {
        return matchingSla;
    }

    public void setMatchingSla(Duration matchingSla) {
        this.matchingSla = matchingSla;
    }
}

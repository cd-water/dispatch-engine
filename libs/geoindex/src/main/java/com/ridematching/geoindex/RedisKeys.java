package com.ridematching.geoindex;

import com.ridematching.domain.driver.DriverId;

/**
 * Redis key 布局的唯一所有者。
 *
 * <p>两个服务读写这些 key：location-service 维护索引，匹配引擎搜索它并在其中认领司机。
 * 如果每个服务各自拼自己的 key 字符串，哪怕只差一个字符也会产生两个互不相交的命名空间——
 * 每次搜索都返回空，而没有任何东西大声报错。把布局集中起来使这种情况不可能发生。
 *
 * <p><strong>Hash tag。</strong>每个 key 都用花括号嵌入 {@code {city}}。Redis Cluster
 * 只对花括号内的部分做哈希，所以同一城市的所有 key 都落在同一个槽上。这不是锦上添花：
 * {@code GEOSEARCH} 无法跨槽，Lua 认领脚本也不行——它要同时触碰三个 key（ADR-0004）。
 */
public final class RedisKeys {

    private final String city;

    public RedisKeys(String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("city must not be blank");
        }
        this.city = city;
    }

    /** 在线司机位置的地理空间有序集合。 */
    public String geoIndex() {
        return "geo:drivers:{%s}".formatted(city);
    }

    /** 以最后一次上报的 epoch 毫秒数为分数的有序集合；失联司机清理器的输入。 */
    public String lastSeen() {
        return "driver:seen:{%s}".formatted(city);
    }

    /** 每个司机的状态哈希：status，以及当前认领的围栏令牌。 */
    public String driverState(DriverId driverId) {
        return "driver:{%s}:%s:state".formatted(city, driverId);
    }

    /** 与 {@link #driverState(DriverId)} 相同，但用于从索引中读回的原始 id。 */
    public String driverState(String driverId) {
        return "driver:{%s}:%s:state".formatted(city, driverId);
    }

    /** 短生命周期的认领标记，保存获胜方的报价令牌。 */
    public String claim(DriverId driverId) {
        return "claim:{%s}:%s".formatted(city, driverId);
    }

    /**
     * {@code SCAN} 模式，匹配本城市中的每一个认领标记。
     *
     * <p>放在这里而不是由调用方拼装，理由与其他所有 key 相同：在别处构建的模式是布局的第二份
     * 定义，两者一旦漂移，扫描就会静默地匹配不到任何东西。一个因 glob 写错而找不到遗留认领的
     * 对账器，看起来和一个无事可做的对账器一模一样。
     */
    public String claimPattern() {
        return "claim:{%s}:*".formatted(city);
    }

    /**
     * 围栏令牌的单调来源。
     *
     * <p>每个城市一个计数器，由 Lua 脚本递增。单调性正是让资源能够拒绝一个在暂停期间租约
     * 过期的工作者写入的依据——这是普通租约锁允许的失败模式（ADR-0004）。
     */
    public String fenceCounter() {
        return "fence:{%s}:counter".formatted(city);
    }

    public String city() {
        return city;
    }
}

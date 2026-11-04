package com.ridematching.location.adapters.in.ws;

/**
 * 一次 GPS 上报的线上格式。字段名刻意很短：在每秒 10,000 帧时，JSON 键在约 1.2 MB/s 的
 * 摄取带宽中占可观的份额。
 *
 * <p>注意这里没有司机 id——它来自已认证的会话，绝不来自载荷。
 *
 * <p>刻意与 {@code DriverLocation} 分为不同类型：领域记录不能携带 Jackson 注解，也不能被
 * 线上格式的关切塑形（ADR-0001）。如果 JSON 解析在性能剖析中显示出来，Protobuf 是文档中
 * 给出的升级路径。
 *
 * @param lat      纬度，度
 * @param lng      经度，度
 * @param heading  罗盘方位 0..360
 * @param speedKph 地面速度
 * @param ts       设备时间戳，epoch 毫秒
 */
public record LocationFrame(double lat, double lng, double heading, double speedKph, long ts) {
}

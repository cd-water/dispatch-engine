package com.ridematching.location.adapters.in.ws;

import tools.jackson.databind.ObjectMapper;
import com.ridematching.domain.driver.DriverId;
import com.ridematching.domain.driver.DriverLocation;
import com.ridematching.domain.geo.Coordinates;
import com.ridematching.location.application.LocationIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.UUID;

/**
 * 终结司机的 WebSocket 连接，并把帧送入摄取路径。
 *
 * <p>每个连接一个虚拟线程（ADR-0006），这正是 10,000 个并发套接字可负担的原因：每个约占
 * 1 KB 堆内存，而不是 1 MB 的操作系统线程栈。
 *
 * <p>司机 id 取自会话（在握手时建立），绝不来自帧体。信任载荷中自报的 id，会让任何已连接的
 * 客户端都能覆盖任何司机的位置——包括把竞争对手挪到城市的另一头。
 */
public class DriverLocationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DriverLocationWebSocketHandler.class);
    private static final String DRIVER_ID_ATTRIBUTE = "driverId";

    private final LocationIngestService ingest;
    private final ObjectMapper objectMapper;

    public DriverLocationWebSocketHandler(LocationIngestService ingest, ObjectMapper objectMapper) {
        this.ingest = ingest;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        DriverId driverId = driverIdOf(session);
        if (driverId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("missing driver id"));
            return;
        }
        ingest.onConnect(driverId);
        log.debug("Driver {} connected on session {}", driverId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        DriverId driverId = driverIdOf(session);
        if (driverId == null) {
            return;
        }

        LocationFrame frame;
        try {
            frame = objectMapper.readValue(message.getPayload(), LocationFrame.class);
        } catch (Exception e) {
            // 格式错误的帧是客户端 bug，不是服务端错误。按 debug 记录并保持连接：
            // 一帧坏数据不该让一辆行驶中的车辆掉线。
            log.debug("Malformed frame from driver {}: {}", driverId, e.getMessage());
            return;
        }

        try {
            ingest.ingest(new DriverLocation(
                    driverId,
                    new Coordinates(frame.lat(), frame.lng()),
                    frame.heading(),
                    frame.speedKph(),
                    Instant.ofEpochMilli(frame.ts())));
        } catch (IllegalArgumentException e) {
            // 坐标、朝向或速度越界——由领域记录拒绝。
            log.debug("Invalid frame from driver {}: {}", driverId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        DriverId driverId = driverIdOf(session);
        if (driverId != null) {
            ingest.onDisconnect(driverId);
            log.debug("Driver {} disconnected: {}", driverId, status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on session {}: {}", session.getId(), exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    private DriverId driverIdOf(WebSocketSession session) {
        Object attribute = session.getAttributes().get(DRIVER_ID_ATTRIBUTE);
        if (attribute instanceof DriverId id) {
            return id;
        }
        if (attribute instanceof String raw) {
            try {
                return new DriverId(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("Failed to close session {}: {}", session.getId(), e.getMessage());
        }
    }
}

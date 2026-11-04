package com.ridematching.location.adapters.in.ws;

import com.ridematching.domain.driver.DriverId;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * 在握手时一次性建立司机身份，并把它固定到会话上。
 *
 * <p>这是连接与司机绑定的唯一位置。之后到达的每一帧都继承该身份，这就是帧体不携带 id
 * 的原因。
 *
 * <p><strong>认证是桩实现。</strong>id 从查询参数读取，这对模拟测试工具没问题，在生产中则
 * 不可接受——真实部署会在这里校验签名令牌，并从其声明中推导出 id。明确指出来，而不是悄悄
 * 留下一个看起来已经完成的样子。
 */
public class DriverHandshakeInterceptor implements HandshakeInterceptor {

    private static final String DRIVER_ID_ATTRIBUTE = "driverId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query == null) {
            return false;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "driverId".equals(pair.substring(0, eq))) {
                try {
                    attributes.put(DRIVER_ID_ATTRIBUTE,
                            new DriverId(UUID.fromString(pair.substring(eq + 1))));
                    return true;
                } catch (IllegalArgumentException e) {
                    return false; // 以 403 拒绝这次握手
                }
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 无事可做
    }
}

package org.thornex.musicparty.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.controller.AuthController;

@Component
@Slf4j
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final AuthController authController;
    private final String adminPassword;

    public WebSocketAuthInterceptor(AuthController authController, AppProperties appProperties) {
        this.authController = authController;
        this.adminPassword = appProperties.getAdminPassword();
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // 只拦截连接命令
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String inputPassword = accessor.getFirstNativeHeader("room-password");

            // 调用 AuthController 获取当前的房间状态
            // 这里的逻辑复用 AuthController 的校验逻辑，但通过内部方法调用
            if (!isPasswordValid(inputPassword)) {
                log.warn("WebSocket Connection Refused: Invalid Room Password. Session: {}", accessor.getSessionId());
                // 抛出异常将直接导致连接断开，并向客户端发送 ERROR 帧
                throw new MessageDeliveryException("INVALID_ROOM_PASSWORD");
            }

            log.info("WebSocket Authenticated: Session {}", accessor.getSessionId());
        }
        return message;
    }

    private boolean isPasswordValid(String input) {
        // 1. 获取当前房间真实密码
        // 注意：我们需要在 AuthController 里增加一个 public 的访问方法获取当前密码值
        String currentRoomPass = authController.getRawPassword();

        // 2. 如果房间还没初始化，或者设置为无密码，允许连接
        if (currentRoomPass == null || currentRoomPass.isEmpty()) {
            return true;
        }

        // 3. 管理员密码（万能钥匙）
        if (adminPassword != null && adminPassword.equals(input)) {
            return true;
        }

        // 4. 比对房间密码
        return currentRoomPass.equals(input);
    }
}
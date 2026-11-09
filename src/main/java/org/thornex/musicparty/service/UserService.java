package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.event.UserCountChangeEvent;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserService {

    // 主存储：Token -> User
    private final Map<String, User> usersByToken = new ConcurrentHashMap<>();

    // 辅助索引：SessionId -> Token (用于快速查找当前发消息的是谁)
    private final Map<String, String> sessionToToken = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher eventPublisher;

    // 延迟任务调度器，用于处理断连抖动
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, ScheduledFuture<?>> pendingLeaveEvents = new ConcurrentHashMap<>();

    private static final long USER_EXPIRATION_MS = 1 * 60 * 60 * 1000L;
    private static final long LEAVE_DELAY_SEC = 10; // 10秒延迟判定真正离开

    public UserService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 处理连接
     * @param sessionId WebSocket Session ID
     * @param tokenFront 前端传来的 Token (可能为空)
     * @param nameFront 前端传来的名字 (可能为空)
     * @return 最终确定的 User 对象
     */
    public synchronized User handleConnect(String sessionId, String tokenFront, String nameFront) {
        User user;

        // 1. 尝试找回老用户
        if (StringUtils.hasText(tokenFront) && usersByToken.containsKey(tokenFront)) {
            user = usersByToken.get(tokenFront);

            // 🟢 检查是否有待执行的“离开”任务，如果有，说明是快速重连，直接取消
            ScheduledFuture<?> pendingLeave = pendingLeaveEvents.remove(user.getToken());
            if (pendingLeave != null) {
                pendingLeave.cancel(false);
                log.info("User {} reconnected quickly, suppressed leave/join logs.", user.getName());
            } else {
                // 如果没有待执行任务，且用户之前是离线状态，且不是游客，则发布加入日志
                if (user.getSessionId() == null && !user.isGuest()) {
                    eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.USER_JOIN, user.getToken(), null));
                }
            }

            log.info("User Reconnected: {} (Token: {}) -> New Session: {}", user.getName(), user.getToken(), sessionId);
            // ... (保持原有逻辑)
            if (user.getSessionId() != null) {
                sessionToToken.remove(user.getSessionId());
            }
            user.setSessionId(sessionId);
        }
        // 2. 新用户注册
        else {
            String newToken = StringUtils.hasText(tokenFront) ? tokenFront : UUID.randomUUID().toString();
            String initialName = StringUtils.hasText(nameFront) ? nameFront : "游客";
            initialName = deduplicateName(initialName);

            user = new User(newToken, sessionId, initialName);
            usersByToken.put(newToken, user);
            log.info("New User Registered: {} (Token: {})", initialName, newToken);
            // 注意：新注册的游客不发加入日志，只有改名后才发
        }

        user.setLastActiveTime(System.currentTimeMillis());
        sessionToToken.put(sessionId, user.getToken());
        eventPublisher.publishEvent(new UserCountChangeEvent(this, getOnlineUserSummaries().size()));
        return user;
    }

    public synchronized Optional<User> disconnectUser(String sessionId) {
        String token = sessionToToken.remove(sessionId);
        if (token == null) return Optional.empty();

        User user = usersByToken.get(token);
        if (user != null) {
            // 🟢 关键修复：多标签页支持
            // 只有当断开的 Session ID 等于用户当前的主 Session ID 时，才认为用户真的掉线了
            // 如果不等，说明用户已经连接了新的 Session (比如打开了新标签页，关闭了旧标签页)，此时忽略旧连接的断开
            if (sessionId.equals(user.getSessionId())) {
                user.setSessionId(null); // 标记离线
                user.setLastActiveTime(System.currentTimeMillis());
                log.info("User Offline (Pending Confirmation): {}", user.getName());

                // 延迟发送离开日志
                if (!user.isGuest()) {
                    String userToken = user.getToken();
                    ScheduledFuture<?> future = scheduler.schedule(() -> {
                        pendingLeaveEvents.remove(userToken);
                        log.info("User Leave Confirmed: {}", user.getName());
                        eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.USER_LEAVE, userToken, null));
                    }, LEAVE_DELAY_SEC, TimeUnit.SECONDS);
                    pendingLeaveEvents.put(userToken, future);
                }

                eventPublisher.publishEvent(new UserCountChangeEvent(this, getOnlineUserSummaries().size()));
                return Optional.of(user);
            } else {
                log.debug("Ignored disconnect for stale session {} (Current: {})", sessionId, user.getSessionId());
            }
        }
        return Optional.empty();
    }

    public Optional<User> getUserBySession(String sessionId) {
        String token = sessionToToken.get(sessionId);
        if (token == null) return Optional.empty();
        return Optional.ofNullable(usersByToken.get(token));
    }

    public Optional<User> getUser(String sessionId) {
        return getUserBySession(sessionId);
    }

    // 🟢 改名逻辑：增加查重
    public synchronized boolean renameUser(String sessionId, String newName) {
        return getUserBySession(sessionId).map(user -> {
            String rawName = newName.trim();
            // 使用一个新的变量 finalName，确保它不被修改
            String finalName = rawName.length() > 20 ? rawName.substring(0, 20) : rawName;

            if (finalName.isEmpty()) return false;

            // 禁止伪装成 游客
            if (finalName.toLowerCase().startsWith("guest") || finalName.startsWith("游客")) {
                log.warn("Rename failed: Cannot use reserved name '{}'", finalName);
                return false;
            }

            // 检查是否重名 (排除自己)
            boolean exists = usersByToken.values().stream()
                    .anyMatch(u -> u.getName().equalsIgnoreCase(finalName) && !u.getToken().equals(user.getToken()));

            if (exists) {
                log.warn("Rename failed: {} is already taken.", finalName);
                return false;
            }

            String oldName = user.getName();
            boolean wasGuest = user.isGuest();

            log.info("User Renamed: '{}' -> '{}'", oldName, finalName);
            user.setName(finalName);
            user.setGuest(false); // 改名成功，移除游客身份

            // 1. 如果是从游客变成正式用户 -> 发布加入事件
            if (wasGuest) {
                eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.USER_JOIN, user.getToken(), null));
            }
            // 2. 如果是正式用户改名 -> 发布系统通知
            else if (!oldName.equals(finalName)) {
                String renameMsg = oldName + " 已更名为 " + finalName;
                eventPublisher.publishEvent(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, null, "SYSTEM", renameMsg));
            }

            return true;
        }).orElse(false);
    }

    // 辅助：名字去重
    private String deduplicateName(String name) {
        String finalName = name;
        int counter = 1;
        while (isNameTaken(finalName)) {
            finalName = name + "_" + counter++;
        }
        return finalName;
    }

    private boolean isNameTaken(String name) {
        return usersByToken.values().stream().anyMatch(u -> u.getName().equalsIgnoreCase(name));
    }

    public boolean bindAccount(String sessionId, String platform, String accountId) {
        return getUserBySession(sessionId).map(user -> {
            user.getBindings().put(platform, accountId);
            return true;
        }).orElse(false);
    }

    public List<UserSummary> getOnlineUserSummaries() {
        return usersByToken.values().stream()
                // 只返回在线用户 (sessionId != null)
                .filter(u -> u.getSessionId() != null)
                .map(user -> new UserSummary(user.getToken(), user.getSessionId(), user.getName(), user.isGuest()))
                .toList();
    }

    /**
     * 获取最近活跃的用户 Token (包括当前在线和正在等待断连确认的用户)
     */
    public Set<String> getRecentlyActiveUserTokens() {
        return usersByToken.values().stream()
                .filter(u -> u.getSessionId() != null || pendingLeaveEvents.containsKey(u.getToken()))
                .map(User::getToken)
                .collect(Collectors.toSet());
    }

    @Scheduled(fixedRate = 3600000)
    public synchronized void cleanupExpiredUsers() {
        long now = System.currentTimeMillis();
        int initialSize = usersByToken.size();

        // removeIf 是线程安全的 (ConcurrentHashMap)
        usersByToken.entrySet().removeIf(entry -> {
            User user = entry.getValue();
            boolean isOffline = user.getSessionId() == null;
            boolean isExpired = (now - user.getLastActiveTime()) > USER_EXPIRATION_MS;

            if (isOffline && isExpired) {
                log.debug("Cleaning up expired user: {} (Token: {})", user.getName(), user.getToken());
                return true; // 删除
            }
            return false; // 保留
        });

        int finalSize = usersByToken.size();
        if (initialSize != finalSize) {
            log.info("Cleanup Complete. Removed {} expired users. Current memory users: {}", (initialSize - finalSize), finalSize);
        }
    }

    /** Caller holds this monitor through durable queue commit and completePlaylistClaim. */
    UserSummary preparePlaylistClaim(String sessionId, String sourceToken, String name) {
        User target = getUser(sessionId).filter(u -> sessionId.equals(u.getSessionId()))
                .orElseThrow(() -> new IllegalArgumentException("连接已失效，请重新连接房间"));
        if (sourceToken.equals(target.getToken())) throw new IllegalArgumentException("这已经是你的歌单");
        User source = usersByToken.get(sourceToken);
        if (source != null && (source.getSessionId() != null
                || System.currentTimeMillis() - source.getLastActiveTime() < 10_000L
                || getRecentlyActiveUserTokens().contains(sourceToken)))
            throw new IllegalArgumentException("原主人在线或刚刚断线，请稍后再认领");
        if (usersByToken.values().stream().anyMatch(u -> !sourceToken.equals(u.getToken())
                && !target.getToken().equals(u.getToken()) && name.equalsIgnoreCase(u.getName())))
            throw new IllegalArgumentException("原歌单名称已被其他成员使用，暂时无法认领");
        return new UserSummary(target.getToken(), sessionId, name, false);
    }

    void completePlaylistClaim(String sourceToken, UserSummary member) {
        User target = usersByToken.get(member.token());
        usersByToken.remove(sourceToken);
        ScheduledFuture<?> leave = pendingLeaveEvents.remove(sourceToken);
        if (leave != null) leave.cancel(false);
        target.setName(member.name());
        target.setGuest(false);
        target.setLastActiveTime(System.currentTimeMillis());
        usersByToken.put(target.getToken(), target);
    }

    public Optional<User> getUserByToken(String token) {
        return Optional.ofNullable(usersByToken.get(token));
    }
}
package com.chen.server.utils;

import com.alibaba.fastjson.JSON;
import com.chen.server.entity.TaskNotification;
import com.chen.server.enums.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ServerEndpoint("/websocket/task-notification/{userId}")
public class TaskNotificationWebSocket {

    private static final Logger logger = LoggerFactory.getLogger(TaskNotificationWebSocket.class);

    // 存储所有活跃的WebSocket会话，以用户ID作为key
    private static final ConcurrentHashMap<Long, TaskNotificationWebSocket> sessions = new ConcurrentHashMap<>();

    private Session session;
    private Long userId;

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("userId") Long userId) {
        this.session = session;
        this.userId = userId;
        sessions.put(userId, this);
        logger.info("用户 {} 的WebSocket连接已建立，当前活跃连接数: {}", userId, sessions.size());
    }

    /**
     * 连接关闭调用的方法
     */
    @OnClose
    public void onClose() {
        if (this.userId != null) {
            sessions.remove(this.userId);
            logger.info("用户 {} 的WebSocket连接已关闭，当前活跃连接数: {}", this.userId, sessions.size());
        }
    }

    /**
     * 收到客户端消息后调用的方法
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        logger.info("来自用户 {}: {}", this.userId, message);
        // 可以处理客户端发送的消息，这里暂时不处理
    }

    /**
     * 发生错误时调用的方法
     */
    @OnError
    public void onError(Session session, Throwable error) {
        logger.error("WebSocket发生错误", error);
        if (this.userId != null) {
            sessions.remove(this.userId);
        }
    }

    /**
     * 向指定用户推送任务完成消息
     */
    public static void sendTaskNotification(Long userId, TaskNotification notification) {
        TaskNotificationWebSocket webSocket = sessions.get(userId);
        if (webSocket != null && webSocket.session.isOpen()) {
            try {
                webSocket.session.getBasicRemote().sendText(JSON.toJSONString(notification));
                logger.info("向用户 {} 推送任务完成消息: taskId={}, type={}",
                           userId, notification.getTaskId(), notification.getTaskTypeName());
            } catch (IOException e) {
                logger.error("推送消息给用户 {} 失败", userId, e);
                // 移除失效的连接
                sessions.remove(userId);
            }
        } else {
            logger.warn("用户 {} 不在线或连接已断开，无法推送消息", userId);
        }
    }

    /**
     * 向所有用户推送消息（可选功能）
     */
    public static void broadcastNotification(TaskNotification notification) {
        for (ConcurrentHashMap.Entry<Long, TaskNotificationWebSocket> entry : sessions.entrySet()) {
            sendTaskNotification(entry.getKey(), notification);
        }
    }

    /**
     * 检查指定用户是否在线
     */
    public static boolean isUserOnline(Long userId) {
        TaskNotificationWebSocket webSocket = sessions.get(userId);
        return webSocket != null && webSocket.session.isOpen();
    }
}

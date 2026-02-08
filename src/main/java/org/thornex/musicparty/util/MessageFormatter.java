package org.thornex.musicparty.util;

import org.thornex.musicparty.event.SystemMessageEvent;

public class MessageFormatter {

    public static String format(SystemMessageEvent event, String userName) {
        String payload = event.getPayload() == null ? "" : event.getPayload();

        if (event.getAction() == null) {
            return payload;
        }

        return switch (event.getAction()) {
            case PLAY_START -> "正在播放来自于 " + userName + " 的 " + payload;
            case USER_JOIN -> userName + " 加入了派对";
            case USER_LEAVE -> userName + " 离开了派对";
            case PLAY -> userName + " 恢复了播放";
            case PAUSE -> userName + " 暂停了播放";
            case SKIP -> userName + " 切到了下一首";
            case ADD -> userName + " 添加了: " + payload;
            case REMOVE -> userName + " 移除了: " + payload;
            case TOP -> userName + " 置顶了: " + payload;
            case LIKE -> userName + " 觉得很赞";
            case IMPORT_PLAYLIST -> userName + " 导入了歌单 (" + payload + "首)";
            case SHUFFLE_ON -> userName + " 开启了随机播放";
            case SHUFFLE_OFF -> userName + " 关闭了随机播放";
            case RESET -> "系统已被重置";
            case SYSTEM_MESSAGE -> payload;
            case ERROR_LOAD -> "加载失败: " + payload;
            default -> userName + " 执行操作: " + event.getAction();
        };
    }
}

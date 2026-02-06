package org.thornex.musicparty.service.command;

import org.thornex.musicparty.dto.User;

public interface ChatCommand {
    /**
     * 命令关键词 (例如 "stream")
     */
    String getCommand();

    /**
     * 执行逻辑
     * @param args 命令参数
     * @param user 调用者
     */
    void execute(String args, User user);
}

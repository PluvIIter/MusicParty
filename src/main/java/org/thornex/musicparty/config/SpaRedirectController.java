package org.thornex.musicparty.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaRedirectController {

    // 修复点：使用正则负向预查 (Negative Lookahead)
    // 含义：匹配不含点的路径，且该路径决不能是 "ws" 或 "api" 开头
    // 这样 WebSocket 请求就会穿透这里，到达真正的 WebSocketHandler
    @RequestMapping(value = "/{path:(?!ws|api|proxy)[^.]*}")
    public String redirect() {
        return "forward:/index.html";
    }
}
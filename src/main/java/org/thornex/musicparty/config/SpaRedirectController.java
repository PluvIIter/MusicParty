package org.thornex.musicparty.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaRedirectController {

    @RequestMapping(value = "/{path:(?!ws|api|media|radio)[^.]*}")
    public String redirect() {
        return "forward:/index.html";
    }
}
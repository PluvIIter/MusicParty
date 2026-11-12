package org.thornex.musicparty.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thornex.musicparty.config.AppProperties;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final AppProperties appProperties;

    public ApiController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @GetMapping("/config")
    public Map<String, String> getConfig() {
        return Map.of("authorName", appProperties.getAuthorName(), "backWords", appProperties.getBackWords());
    }
}

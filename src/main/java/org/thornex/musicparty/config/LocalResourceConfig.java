package org.thornex.musicparty.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class LocalResourceConfig implements WebMvcConfigurer {

    // 存储目录
    public static final String CACHE_DIR = "cached_media";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 创建目录（如果不存在）
        File dir = new File(CACHE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 将 /media/** 映射到本地文件系统
        // file:cached_media/
        registry.addResourceHandler("/media/**")
                .addResourceLocations(dir.toURI().toString());
    }
}
package org.thornex.musicparty.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        // 配置 ExchangeStrategies 来增加缓冲区大小
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 设置为 10MB
                .build();

        // 关键：显式使用 JDK 系统解析器（DefaultAddressResolverGroup.INSTANCE），而非 reactor-netty 默认的异步 DNS 解析器。
        // Netty 的 DnsNameResolver 在 Windows 上经常读不到系统 DNS（platformDefault() 失败），会回退到 Google Public DNS
        // （8.8.8.8/8.8.4.4）——国内网络不可达 → api.bilibili.com 解析失败 → UnknownHostException → 整个 B站源 500。
        // JDK 解析器走 OS 的 getaddrinfo，跟随系统/适配器 DNS，与浏览器解析结果一致。
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies) // 应用配置
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build();
    }
}

package org.thornex.musicparty;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.thornex.musicparty.config.AppProperties;

@SpringBootApplication
@EnableScheduling // Enable background task scheduling
@EnableConfigurationProperties(AppProperties.class) // Enable custom properties class
public class MusicPartyApplication {

	public static void main(String[] args) {
		SpringApplication.run(MusicPartyApplication.class, args);
	}

}

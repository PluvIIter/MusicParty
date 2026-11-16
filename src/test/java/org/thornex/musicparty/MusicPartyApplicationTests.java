package org.thornex.musicparty;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"app.music-api.sources.qqmusic.enabled=false", "app.music-api.qqmusic.cookie=invalid-deployment-value"})
class MusicPartyApplicationTests {

	@Test
	void contextLoads() {
	}

}

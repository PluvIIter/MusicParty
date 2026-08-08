package org.thornex.musicparty.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppPropertiesTest {

    @Test
    void privateDjDefaultsAreOff() {
        AppProperties props = new AppProperties();
        AppProperties.PrivateDjConfig c = props.getPrivateDj();
        assertEquals("OFF", c.getMode()); // 默认关闭，模式即开关
        assertFalse(c.isFillBlankEnabled());
        assertFalse(c.isJoinQueueEnabled());
        assertFalse(c.isCustodyEnabled());
    }
}

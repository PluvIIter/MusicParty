package org.thornex.musicparty.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppPropertiesTest {

    @Test
    void privateDjDefaultsAreOffAndFm() {
        AppProperties props = new AppProperties();
        AppProperties.PrivateDjConfig c = props.getPrivateDj();
        assertFalse(c.isMasterEnabled());
        assertEquals("FM", c.getMode());
        assertFalse(c.isFillBlankEnabled());
        assertFalse(c.isJoinQueueEnabled());
        assertFalse(c.isCustodyEnabled());
    }
}

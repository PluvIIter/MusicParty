package org.thornex.musicparty.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thornex.musicparty.config.AppProperties;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiConfigControllerTest {
    @Test
    void publicApplicationConfigDoesNotRequireRoomAuthorization() throws Exception {
        var properties = new AppProperties();
        properties.setAuthorName("Author");
        properties.setBackWords("Welcome");
        var mvc = MockMvcBuilders.standaloneSetup(new ApiController(properties)).build();
        mvc.perform(get("/api/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.authorName").value("Author"))
                .andExpect(jsonPath("$.backWords").value("Welcome"));
    }
}

package ru.itmo.ddia.hw1.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void crudRoundTrip() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"widget\",\"description\":\"a widget\",\"quantity\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();

        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/items/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("widget"));

        mockMvc.perform(put("/api/v1/items/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"widget-v2\",\"description\":\"updated\",\"quantity\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("widget-v2"))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(delete("/api/v1/items/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/items/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Item not found: " + id));
    }

    @Test
    void returnsBadRequestOnInvalidBody() throws Exception {
        mockMvc.perform(post("/api/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request body validation failed"));
    }

    @Test
    void returnsNotFoundOnUpdateOfMissingItem() throws Exception {
        mockMvc.perform(put("/api/v1/items/{id}", "does-not-exist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Item not found: does-not-exist"));
    }

    @Test
    void returnsBadRequestOnOutOfRangeLimit() throws Exception {
        mockMvc.perform(get("/api/v1/items").param("limit", "9999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request parameter validation failed"));
    }

    @Test
    void returnsBadRequestOnMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void exposesInstanceParametersOnInfoEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.pod").exists())
                .andExpect(jsonPath("$.instance.datacenter").exists())
                .andExpect(jsonPath("$.instance.deployment").exists());
    }
}
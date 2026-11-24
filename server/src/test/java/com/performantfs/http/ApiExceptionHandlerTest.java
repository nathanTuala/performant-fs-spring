package com.performantfs.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    record Named(@NotBlank String name) {}

    @RestController
    static class Endpoints {
        @GetMapping("/conflict")
        void conflict() {
            throw new ApiException(ErrorCode.NAME_CONFLICT, "Taken", Map.of("name", "a.txt"));
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("database password is hunter2");
        }

        @PostMapping("/named")
        void named(@Valid @RequestBody Named body) {}
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Endpoints())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void expectedFailuresKeepTheirCodeStatusAndDetails() throws Exception {
        mvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("name_conflict"))
                .andExpect(jsonPath("$.error.message").value("Taken"))
                .andExpect(jsonPath("$.error.name").value("a.txt"));
    }

    @Test
    void invalidBodiesAre400() throws Exception {
        mvc.perform(post("/named").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("bad_request"));
        mvc.perform(post("/named").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Malformed JSON body"));
    }

    @Test
    void unexpectedFailuresAreOpaque500s() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal"))
                .andExpect(jsonPath("$.error.message").value("Something went wrong"));
    }
}

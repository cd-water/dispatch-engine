package com.ridematching.dispatch.adapters.in.rest;

import com.ridematching.dispatch.application.DriverAlreadyAssignedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 断言 API 在请求被拒绝时能解释清楚自己。
 *
 * <p>这里要防的 bug 不是崩溃——而是 Spring 默认的错误响应体
 * {@code {"timestamp","status":400,"error":"Bad Request","path"}}，它既不点出字段，也不
 * 说明原因。这个响应技术上正确，实际上毫无用处，而在这些处理器出现之前，这个 API 返回的
 * 就是它。
 *
 * <p>用 standalone MockMvc 驱动一个最小控制器，而不是真实控制器：被测试的行为属于 advice，
 * 把它绑到真实控制器的依赖上，测的就成了装配而不是错误契约。
 */
class RestExceptionHandlerTest {

    /** 最小端点，唯一职责是以 advice 必须处理的方式失败。 */
    @RestController
    static class ProbeController {

        @PostMapping(path = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        RideRequestDto accept(@Valid @RequestBody RideRequestDto body) {
            return body;
        }

        @GetMapping("/probe/conflict")
        String conflict() {
            throw new DriverAlreadyAssignedException("driver already holds an active trip", null);
        }

        @GetMapping("/probe/illegal")
        String illegal() {
            throw new IllegalArgumentException("Unknown vehicleClass: HELICOPTER");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new RestExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a validation failure names every offending field")
    void validationFailureNamesFields() throws Exception {
        // 缺少 riderId，且纬度越界。
        String body = """
                {"pickupLat": 999.0, "pickupLng": 31.2357,
                 "dropoffLat": 30.05, "dropoffLng": 31.24}
                """;

        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                // 关键之处：调用方得知的是哪个字段，而不只是"有东西失败了"。
                .andExpect(jsonPath("$.errors.riderId").exists())
                .andExpect(jsonPath("$.errors.pickupLat").exists());
    }

    @Test
    @DisplayName("malformed JSON explains the expected shape")
    void malformedJsonIsExplained() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request body"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("riderId")));
    }

    @Test
    @DisplayName("the parser's internal message is never echoed to the client")
    void parserDetailIsNotDisclosed() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupLat\": \"not-a-number\"}"))
                .andExpect(status().isBadRequest())
                // Jackson 的消息会点出 Java 类和字段路径。返回这些等于递出一张内部
                // 结构图，对任何合法客户端都没有帮助。
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("com.ridematching"))));
    }

    @Test
    @DisplayName("a lost driver claim is a 409, not a 500")
    void driverConflictIsNotAServerError() throws Exception {
        // 在争用下这正是 ADR-0004 的保证在生效。返回 500 会因为系统完全按设计运行而
        // 把人半夜叫起来。
        mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Driver unavailable"));
    }

    @Test
    @DisplayName("a domain rejection surfaces its own message")
    void domainRejectionKeepsItsMessage() throws Exception {
        mvc.perform(get("/probe/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("HELICOPTER")));
    }
}

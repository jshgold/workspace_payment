package com.payment.common.exception;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(GlobalExceptionHandlerTest.StubController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.StubController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("BusinessException이 발생하면 ErrorCode의 상태코드와 코드·메시지로 응답한다")
    void businessException() throws Exception {
        // given // when // then
        mockMvc.perform(get("/stub/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.message").value("재고가 부족합니다."))
                .andExpect(jsonPath("$.errors", hasSize(0)));
    }

    @Test
    @DisplayName("Bean Validation에 실패하면 400과 필드별 오류 목록을 응답한다")
    void validationFailure() throws Exception {
        // given
        String body = """
                {"name": "", "price": 0}
                """;

        // when // then
        mockMvc.perform(post("/stub/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"))
                .andExpect(jsonPath("$.errors", hasSize(2)));
    }

    @Test
    @DisplayName("JSON 형식이 깨진 요청이면 400과 INVALID_REQUEST_FORMAT으로 응답한다")
    void malformedJson() throws Exception {
        // given // when // then
        mockMvc.perform(post("/stub/validate").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("존재하지 않는 경로로 요청하면 404와 RESOURCE_NOT_FOUND로 응답한다")
    void unknownPath() throws Exception {
        // given // when // then
        mockMvc.perform(get("/no/such/path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드면 405로 응답한다")
    void methodNotAllowed() throws Exception {
        // given // when // then
        mockMvc.perform(post("/stub/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("COMMON_004"));
    }

    @Test
    @DisplayName("예상하지 못한 예외는 내부 정보를 숨기고 500으로 응답한다")
    void unexpectedException() throws Exception {
        // given // when // then
        mockMvc.perform(get("/stub/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_999"))
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
    }

    @RestController
    static class StubController {

        @GetMapping("/stub/business")
        void business() {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "재고가 부족합니다.");
        }

        @GetMapping("/stub/boom")
        void boom() {
            throw new NullPointerException("secret internal detail");
        }

        @PostMapping("/stub/validate")
        void validate(@Valid @RequestBody StubRequest request) {
        }
    }

    record StubRequest(@NotBlank String name, @Positive int price) {
    }
}

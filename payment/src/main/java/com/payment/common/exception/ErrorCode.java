package com.payment.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
    INVALID_REQUEST_FORMAT(HttpStatus.BAD_REQUEST, "COMMON_002", "요청 형식이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_003", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_004", "지원하지 않는 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 내부 오류가 발생했습니다."),

    // 상품
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_001", "상품을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}

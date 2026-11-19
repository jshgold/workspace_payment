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
    STATE_CONFLICT(HttpStatus.CONFLICT, "COMMON_005", "현재 상태에서는 처리할 수 없는 요청입니다."),
    LOCK_ACQUISITION_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "COMMON_006", "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 내부 오류가 발생했습니다."),

    // 상품
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_001", "상품을 찾을 수 없습니다."),
    STOCK_NOT_AVAILABLE(HttpStatus.CONFLICT, "PRODUCT_002", "재고가 부족하거나 판매할 수 없는 상품입니다."),
    STOCK_CONFLICT_RETRY_EXCEEDED(HttpStatus.CONFLICT, "PRODUCT_003", "동시 요청이 많아 재고 차감에 실패했습니다. 잠시 후 다시 시도해주세요."),

    // 주문
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_001", "주문을 찾을 수 없습니다."),
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "ORDER_002", "결제할 수 없는 주문입니다. 새 주문으로 결제해주세요."),

    // 결제 (PG)
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "PAYMENT_004", "결제 승인 금액이 주문 금액과 일치하지 않습니다."),
    PAYMENT_REQUEST_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT_005", "요청 금액이 주문 금액과 일치하지 않습니다."),
    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "PAYMENT_006", "같은 요청이 처리 중입니다. 같은 멱등키로 잠시 후 다시 확인해주세요."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_007", "이미 다른 주문에 사용된 멱등키입니다."),
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_001", "결제가 거절되었습니다."),
    PAYMENT_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "PAYMENT_002", "결제사 응답이 지연되어 결제 결과를 확인할 수 없습니다."),
    PAYMENT_CANCEL_FAILED(HttpStatus.BAD_GATEWAY, "PAYMENT_003", "결제 취소에 실패했습니다."),
    PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "PAYMENT_008", "결제 서비스 연동에 문제가 있어 결제를 진행할 수 없습니다. 결제는 승인되지 않았습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}

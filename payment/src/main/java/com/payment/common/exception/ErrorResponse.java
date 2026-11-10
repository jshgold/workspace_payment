package com.payment.common.exception;

import java.util.List;

import org.springframework.validation.FieldError;

public record ErrorResponse(
        String code,
        String message,
        List<FieldViolation> errors
) {

    public record FieldViolation(String field, String reason) {

        public static FieldViolation from(FieldError fieldError) {
            return new FieldViolation(fieldError.getField(), fieldError.getDefaultMessage());
        }
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), List.of());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.getCode(), message, List.of());
    }

    public static ErrorResponse of(ErrorCode errorCode, List<FieldViolation> errors) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), errors);
    }
}

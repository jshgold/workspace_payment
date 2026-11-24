package com.payment.payment.service;

public record StartCancelCommand(Long orderId, String idempotencyKey, String reason) {
}

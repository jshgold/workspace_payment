package com.payment.order.controller;

import com.payment.order.dto.ExpirationRoundResult;
import com.payment.order.service.OrderExpirationService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문 만료 수동 호출. 스케줄러와 같은 회차를 바로 한 번 돌린다. 인증은 범위 밖이다. (intent 7절) */
@RestController
@RequestMapping("/api/admin/orders/expirations")
@RequiredArgsConstructor
public class OrderExpirationController {

    private final OrderExpirationService expirationService;

    @PostMapping
    public ResponseEntity<ExpirationRoundResult> expire() {
        return ResponseEntity.ok(expirationService.expire());
    }
}

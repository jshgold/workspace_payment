package com.payment.payment.controller;

import com.payment.payment.dto.ReconciliationRoundResult;
import com.payment.payment.facade.PaymentReconciliationFacade;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대사 수동 호출. 스케줄러와 같은 회차를 바로 한 번 돌린다. (4-6 E2E, 장애 후 즉시 확인용)
 * 인증은 범위 밖이다. (intent 7절)
 */
@RestController
@RequestMapping("/api/admin/reconciliations")
@RequiredArgsConstructor
public class ReconciliationController {

    private final PaymentReconciliationFacade reconciliationFacade;

    @PostMapping("/payments")
    public ResponseEntity<ReconciliationRoundResult> reconcilePayments() {
        return ResponseEntity.ok(reconciliationFacade.reconcilePayments());
    }

    @PostMapping("/cancels")
    public ResponseEntity<ReconciliationRoundResult> reconcileCancels() {
        return ResponseEntity.ok(reconciliationFacade.reconcileCancels());
    }
}

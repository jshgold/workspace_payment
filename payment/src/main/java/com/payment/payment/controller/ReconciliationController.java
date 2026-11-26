package com.payment.payment.controller;

import com.payment.payment.dto.ReconciliationRoundResult;
import com.payment.payment.facade.PaymentReconciliationFacade;
import com.payment.payment.service.PaymentReconciliationStateService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final PaymentReconciliationStateService reconciliationStateService;

    @PostMapping("/payments")
    public ResponseEntity<ReconciliationRoundResult> reconcilePayments() {
        return ResponseEntity.ok(reconciliationFacade.reconcilePayments());
    }

    @PostMapping("/cancels")
    public ResponseEntity<ReconciliationRoundResult> reconcileCancels() {
        return ResponseEntity.ok(reconciliationFacade.reconcileCancels());
    }

    /** 취소 확인 필요(CANCEL_REVIEW) 건을 사람이 원인을 고친 뒤 다시 대사 대상(CANCEL_UNKNOWN)으로 되돌린다. CANCEL_REVIEW가 아니면 409. */
    @PostMapping("/cancel-reviews/{orderId}/reopen")
    public ResponseEntity<Void> reopenCancelReview(@PathVariable Long orderId) {
        reconciliationStateService.reopenCancelReview(orderId);
        return ResponseEntity.noContent().build();
    }
}

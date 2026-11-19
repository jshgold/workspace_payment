package com.payment.payment.infrastructure;

import com.payment.payment.domain.PaymentCompletedEvent;
import com.payment.payment.domain.PaymentNotifier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LogPaymentNotifier implements PaymentNotifier {

    @Override
    public void notifyPaid(PaymentCompletedEvent event) {
        log.info("[결제 완료 알림] orderId={}, paymentId={}, paymentKey={}, amount={}",
                event.orderId(), event.paymentId(), event.paymentKey(), event.amount());
    }
}

package com.payment.payment.facade;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [측정 전용 계측] 결제 승인 요청 1건을 구간(멱등키 선점, 주문 선점, PG 승인, 결과 반영 ...)별로 나눠 소요 시간을 잰다.
 * (docs/troubleshooting/payment-transaction-granularity.md)
 * <p>
 * 요청이 끝나면 {@code payment.timing} 로거에 한 줄로 남긴다. 기본은 꺼져 있고
 * {@code --logging.level.payment.timing=debug}로 켠다. 꺼져 있으면 시간을 재지 않고 그대로 실행만 한다.
 * <p>
 * 한 요청은 한 스레드에서 처리되므로 {@link ThreadLocal}에 기록한다. 시작한 쪽이 반드시 {@link #finish}를
 * {@code finally}에서 불러야 스레드 풀 재사용 시 기록이 남지 않는다. 비즈니스 흐름은 바꾸지 않는다.
 */
final class PaymentSegmentTimer {

    private static final Logger log = LoggerFactory.getLogger("payment.timing");
    private static final ThreadLocal<Recording> CURRENT = new ThreadLocal<>();

    private PaymentSegmentTimer() {
    }

    static void start(Long orderId) {
        if (!log.isDebugEnabled()) {
            return;
        }
        CURRENT.set(new Recording(orderId, System.nanoTime()));
    }

    /** 구간 하나를 잰다. 예외가 나도 걸린 시간은 기록한다. 기록 중이 아니면 그대로 실행만 한다. */
    static <T> T time(String segment, Supplier<T> action) {
        Recording recording = CURRENT.get();
        if (recording == null) {
            return action.get();
        }
        long startedAt = System.nanoTime();
        try {
            return action.get();
        } finally {
            recording.add(segment, System.nanoTime() - startedAt);
        }
    }

    static void time(String segment, Runnable action) {
        time(segment, () -> {
            action.run();
            return null;
        });
    }

    /** 기록을 로그로 남기고 지운다. 기록 중이 아니면 아무것도 하지 않는다. */
    static void finish(String result) {
        Recording recording = CURRENT.get();
        if (recording == null) {
            return;
        }
        CURRENT.remove();
        log.debug(recording.summary(result, System.nanoTime()));
    }

    private static final class Recording {

        private final Long orderId;
        private final long startedAt;
        private final StringBuilder segments = new StringBuilder();
        private long measuredNanos;

        private Recording(Long orderId, long startedAt) {
            this.orderId = orderId;
            this.startedAt = startedAt;
        }

        private void add(String segment, long nanos) {
            segments.append(' ').append(segment).append('=').append(toMillis(nanos));
            measuredNanos += nanos;
        }

        /** etc = 전체에서 잰 구간을 뺀 나머지 (Facade 자체 코드, 예외 처리, 로그 출력 등) */
        private String summary(String result, long finishedAt) {
            long total = finishedAt - startedAt;
            return "orderId=" + orderId + " result=" + result + segments
                    + " etc=" + toMillis(total - measuredNanos) + " total=" + toMillis(total);
        }

        private static String toMillis(long nanos) {
            return String.format("%.1fms", nanos / 1_000_000.0);
        }
    }
}

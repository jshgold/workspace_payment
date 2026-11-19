package com.payment.common.util;

/**
 * [측정 전용 계측] 현재 스레드에서 "커넥션을 빌리는 데 기다린 시간"과 "COMMIT에 걸린 시간"을 모은다.
 * (docs/troubleshooting/payment-transaction-granularity.md)
 * <p>
 * {@link #begin()}으로 기록을 시작한 스레드에서만 쌓인다. 시작하지 않은 스레드(다른 API, 비동기 리스너 등)에서는
 * 아무것도 하지 않는다. 값을 쌓는 쪽은 {@code JdbcTimingDataSource}, 읽는 쪽은 {@code PaymentSegmentTimer}다.
 */
public final class JdbcTiming {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private JdbcTiming() {
    }

    public static void begin() {
        CURRENT.set(new Snapshot());
    }

    /** 기록을 끝내고 모은 값을 돌려준다. 기록 중이 아니었으면 빈 값을 돌려준다. */
    public static Snapshot end() {
        Snapshot snapshot = CURRENT.get();
        CURRENT.remove();
        return snapshot == null ? new Snapshot() : snapshot;
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    public static void addConnectionWait(long nanos) {
        Snapshot snapshot = CURRENT.get();
        if (snapshot != null) {
            snapshot.connectionWaitNanos += nanos;
            snapshot.connections++;
        }
    }

    public static void addCommit(long nanos) {
        Snapshot snapshot = CURRENT.get();
        if (snapshot != null) {
            snapshot.commitNanos += nanos;
        }
    }

    public static final class Snapshot {

        private long connectionWaitNanos;
        private long commitNanos;
        private int connections;

        public long connectionWaitNanos() {
            return connectionWaitNanos;
        }

        public long commitNanos() {
            return commitNanos;
        }

        public int connections() {
            return connections;
        }
    }
}

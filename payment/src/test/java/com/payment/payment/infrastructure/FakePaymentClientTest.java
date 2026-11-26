package com.payment.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;

import com.payment.payment.domain.PaymentCancelDeclinedException;
import com.payment.payment.domain.PaymentDeclinedException;
import com.payment.payment.domain.PaymentGatewayException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;
import com.payment.payment.domain.PgCancelResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakePaymentClientTest {

    FakePaymentClient client;

    @BeforeEach
    void setUp() {
        client = new FakePaymentClient();
    }

    private PgApproveRequest request() {
        return new PgApproveRequest("order-no-1", "payment-key-1", 30_000L);
    }

    @Test
    @DisplayName("기본 시나리오는 승인이며, 요청 금액 그대로 승인하고 거래 ID를 발급한다")
    void approveByDefault() {
        // when
        PgApproveResult result = client.approve(request());

        // then
        assertThat(result.pgTransactionId()).startsWith("fake-");
        assertThat(result.approvedAmount()).isEqualTo(30_000L);
        assertThat(client.approveCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("거절 시나리오에서는 사유를 담은 PaymentDeclinedException이 발생한다")
    void decline() {
        // given
        client.willDecline("잔액 부족");

        // when // then
        assertThatThrownBy(() -> client.approve(request()))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("잔액 부족");
        assertThat(client.approveCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("타임아웃 시나리오에서는 지연 시간만큼 기다린 뒤 PaymentTimeoutException이 발생한다")
    void timeoutAfterDelay() {
        // given
        client.willTimeout();
        client.delay(Duration.ofMillis(200));

        // when
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // then
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(200);
    }

    @Test
    @DisplayName("지연 시나리오에서는 승인이 성공하되 설정한 시간만큼 늦게 응답한다")
    void approveWithDelay() {
        // given
        client.delay(Duration.ofMillis(200));

        // when
        long start = System.nanoTime();
        PgApproveResult result = client.approve(request());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // then
        assertThat(result.approvedAmount()).isEqualTo(30_000L);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(200);
    }

    @Test
    @DisplayName("취소하면 취소된 거래 ID와 호출 횟수가 기록된다")
    void cancel() {
        // when
        client.cancel("fake-1", "재고 부족", "cancel-key-1");

        // then
        assertThat(client.cancelCallCount()).isEqualTo(1);
        assertThat(client.canceledTransactionIds()).containsExactly("fake-1");
    }

    @Test
    @DisplayName("취소 실패 시나리오에서는 PaymentCancelDeclinedException이 발생하고 취소 기록은 남지 않는다")
    void cancelFails() {
        // given
        client.willFailCancel();

        // when // then
        assertThatThrownBy(() -> client.cancel("fake-1", "재고 부족", "cancel-key-1"))
                .isInstanceOf(PaymentCancelDeclinedException.class);
        assertThat(client.cancelCallCount()).isEqualTo(1);
        assertThat(client.canceledTransactionIds()).isEmpty();
    }

    @Test
    @DisplayName("취소 타임아웃 시나리오에서는 PaymentTimeoutException, 연결 실패 시나리오에서는 PaymentGatewayException이 발생한다")
    void cancelTimeoutAndConnectionFailure() {
        // given
        client.willTimeoutCancel();

        // when // then
        assertThatThrownBy(() -> client.cancel("fake-1", "단순 변심", "cancel-key-1")).isInstanceOf(PaymentTimeoutException.class);

        // given
        client.willFailCancelConnection();

        // when // then
        assertThatThrownBy(() -> client.cancel("fake-1", "단순 변심", "cancel-key-1")).isInstanceOf(PaymentGatewayException.class);
        assertThat(client.cancelCallCount()).isEqualTo(2);
        assertThat(client.canceledTransactionIds()).isEmpty();
    }

    @Test
    @DisplayName("이미 취소됨 시나리오에서는 예외 없이 alreadyCanceled=true를 돌려준다")
    void cancelAlreadyCanceled() {
        // given
        client.willAlreadyCanceled();

        // when
        PgCancelResult result = client.cancel("fake-1", "단순 변심", "cancel-key-1");

        // then
        assertThat(result.alreadyCanceled()).isTrue();
        assertThat(client.cancelCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reset하면 시나리오와 호출 기록이 초기 상태로 돌아간다")
    void reset() {
        // given
        client.willDecline("x");
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentDeclinedException.class);
        client.reset();

        // when
        PgApproveResult result = client.approve(request());

        // then
        assertThat(result.approvedAmount()).isEqualTo(30_000L);
        assertThat(client.approveCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 PG 멱등키로 다시 승인하면 처리하지 않고 첫 승인 결과(같은 거래 ID)를 돌려준다")
    void approveReplaysFirstResultForSameKey() {
        // given
        PgApproveResult first = client.approve(request());

        // when
        PgApproveResult second = client.approve(request());

        // then
        assertThat(second).isEqualTo(first);
        assertThat(client.approveCallCount()).isEqualTo(2);
        assertThat(client.approveProcessedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 PG 멱등키면 시나리오가 바뀌어도 첫 거절을 그대로 돌려준다 (토스처럼 첫 응답 저장)")
    void approveReplaysFirstDeclineForSameKey() {
        // given
        client.willDecline("한도 초과");
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentDeclinedException.class);
        client.willApprove();

        // when // then
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentDeclinedException.class);
        assertThat(client.approveProcessedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답 유실: 승인은 처리하고 타임아웃을 던진 뒤, 같은 키로 다시 오면 처리한 결과를 돌려준다")
    void lostApproveResponseThenReplay() {
        // given
        client.willLoseApproveResponseOnce();
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);

        // when
        PgApproveResult result = client.approve(request());

        // then
        assertThat(result.approvedAmount()).isEqualTo(30_000L);
        assertThat(client.approveProcessedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("타임아웃 시나리오는 PG가 처리하지 않은 것으로 보고 저장하지 않는다. 다음 호출은 새로 처리된다")
    void timeoutIsNotStored() {
        // given
        client.willTimeout();
        assertThatThrownBy(() -> client.approve(request())).isInstanceOf(PaymentTimeoutException.class);
        client.willApprove();

        // when
        PgApproveResult result = client.approve(request());

        // then
        assertThat(result.approvedAmount()).isEqualTo(30_000L);
        assertThat(client.approveProcessedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("취소는 같은 PG 멱등키면 시나리오가 바뀌어도 첫 거절을 돌려주고, 새 키면 새로 처리한다")
    void cancelReplaysBySameKeyOnly() {
        // given
        client.willFailCancel();
        assertThatThrownBy(() -> client.cancel("fake-1", "단순 변심", "cancel-1"))
                .isInstanceOf(PaymentCancelDeclinedException.class);
        client.willCancel();

        // when
        Throwable sameKey = catchThrowable(() -> client.cancel("fake-1", "단순 변심", "cancel-1"));
        PgCancelResult newKey = client.cancel("fake-1", "단순 변심", "cancel-2");

        // then
        assertThat(sameKey).isInstanceOf(PaymentCancelDeclinedException.class);
        assertThat(newKey.alreadyCanceled()).isFalse();
        assertThat(client.cancelCallCount()).isEqualTo(3);
        assertThat(client.cancelProcessedCount()).isEqualTo(2);
    }
}

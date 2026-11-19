package com.payment.payment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** [측정 전용 계측] 구간 시간이 한 줄로 남는지, 꺼져 있을 때 흐름에 영향이 없는지 확인한다. */
class PaymentSegmentTimerTest {

    private final Logger timingLogger = (Logger) LoggerFactory.getLogger("payment.timing");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        originalLevel = timingLogger.getLevel();
        appender.start();
        timingLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        timingLogger.detachAppender(appender);
        timingLogger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("기록 중이면 구간들이 호출 순서대로 한 줄에 남고, 예외가 난 구간도 기록된다")
    void recordsSegmentsInOrderIncludingFailedOne() {
        // given
        timingLogger.setLevel(Level.DEBUG);
        PaymentSegmentTimer.start(1L);

        // when
        String value = PaymentSegmentTimer.time("startPaying", () -> "target");
        assertThatThrownBy(() -> PaymentSegmentTimer.time("complete", () -> {
            throw new IllegalStateException("재고 부족");
        })).isInstanceOf(IllegalStateException.class);
        PaymentSegmentTimer.finish("FAIL");

        // then
        assertThat(value).isEqualTo("target");
        List<String> messages = messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst())
                .matches("orderId=1 result=FAIL startPaying=\\d+\\.\\dms complete=\\d+\\.\\dms etc=-?\\d+\\.\\dms total=\\d+\\.\\dms");
    }

    @Test
    @DisplayName("finish 뒤에는 기록이 지워져, 같은 스레드의 다음 요청에 이전 구간이 섞이지 않는다")
    void finishClearsRecording() {
        // given
        timingLogger.setLevel(Level.DEBUG);
        PaymentSegmentTimer.start(1L);
        PaymentSegmentTimer.time("begin", () -> { });
        PaymentSegmentTimer.finish("OK");

        // when
        PaymentSegmentTimer.start(2L);
        PaymentSegmentTimer.time("succeed", () -> { });
        PaymentSegmentTimer.finish("OK");

        // then
        assertThat(messages().get(1)).startsWith("orderId=2 result=OK succeed=").doesNotContain("begin=");
    }

    @Test
    @DisplayName("로그 레벨이 DEBUG가 아니면 시간을 재지 않고 실행 결과만 그대로 돌려준다")
    void disabledWhenNotDebug() {
        // given
        timingLogger.setLevel(Level.INFO);

        // when
        PaymentSegmentTimer.start(1L);
        String value = PaymentSegmentTimer.time("startPaying", () -> "target");
        PaymentSegmentTimer.finish("OK");

        // then
        assertThat(value).isEqualTo("target");
        assertThat(appender.list).isEmpty();
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}

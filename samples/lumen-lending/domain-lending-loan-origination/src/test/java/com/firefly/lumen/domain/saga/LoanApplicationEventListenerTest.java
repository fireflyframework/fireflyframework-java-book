package com.firefly.lumen.domain.saga;

import com.firefly.lumen.domain.client.LoanOriginationClient;
import com.firefly.lumen.domain.client.StubLoanOriginationClient;
import com.firefly.lumen.domain.event.LoanApplicationEventRecorder;
import com.firefly.lumen.domain.event.LoanApplicationRegisteredEvent;
import org.fireflyframework.eda.listener.EventListenerProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EDA integration test: proves that an {@code @EventListener}-annotated method is wired into
 * the Firefly EDA runtime and receives a {@link LoanApplicationRegisteredEvent}.
 *
 * <p>Events are dispatched through the framework's {@link EventListenerProcessor} — the same
 * component every transport (Kafka, RabbitMQ, in-JVM) funnels delivered messages into. By
 * driving {@code processEvent(payload, headers)} directly we exercise the real annotation
 * discovery and method invocation with <strong>no broker and no Docker</strong>; only the
 * external transport hop is elided. The listener bean ({@link LoanApplicationEventRecorder})
 * and its {@code @EventListener} registration are entirely real.
 */
@SpringBootTest
@Import(LoanApplicationEventListenerTest.StubConfig.class)
class LoanApplicationEventListenerTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        LoanOriginationClient loanOriginationClient() {
            return new StubLoanOriginationClient();
        }
    }

    @Autowired
    private EventListenerProcessor processor;

    @Autowired
    private LoanApplicationEventRecorder recorder;

    @Test
    void annotatedListener_receivesDispatchedEvent() {
        var event = new LoanApplicationRegisteredEvent(UUID.randomUUID(), "Ada Lovelace", 250_000L);

        // The processor routes the payload to every @EventListener whose type matches.
        StepVerifier.create(processor.processEvent(event, Map.of()))
                .verifyComplete();

        assertThat(recorder.received()).containsExactly(event);
    }
}

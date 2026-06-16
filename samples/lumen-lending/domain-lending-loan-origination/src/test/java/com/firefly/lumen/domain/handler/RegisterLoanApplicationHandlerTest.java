package com.firefly.lumen.domain.handler;

import com.firefly.lumen.domain.client.StubLoanOriginationClient;
import com.firefly.lumen.domain.command.RegisterLoanApplicationCommand;
import com.firefly.lumen.domain.event.LoanApplicationRegisteredEvent;
import org.fireflyframework.eda.publisher.EventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link RegisterLoanApplicationHandler}. No Spring context: the handler is
 * exercised in isolation against the stub client and a mocked EDA publisher, proving
 * {@code doHandle} calls the core SDK seam and returns the new application id.
 */
class RegisterLoanApplicationHandlerTest {

    @Test
    void doHandle_callsClient_andReturnsCreatedId() {
        var client = new StubLoanOriginationClient();
        EventPublisher eventPublisher = mock(EventPublisher.class);
        when(eventPublisher.publish(any(), anyString())).thenReturn(Mono.empty());

        var handler = new RegisterLoanApplicationHandler(client, eventPublisher);
        var command = new RegisterLoanApplicationCommand("Ada Lovelace", 250_000L);

        // handle(...) is the framework's public entry point; it wraps doHandle(...).
        StepVerifier.create(handler.handle(command))
                .assertNext(id -> assertThat(id).isEqualTo(client.createdApplications().get(0)))
                .verifyComplete();

        assertThat(client.calls()).containsExactly("create:Ada Lovelace:250000");
        verify(eventPublisher).publish(any(), anyString());
    }

    @Test
    void doHandle_publishesRegisteredEvent_overEda() {
        var client = new StubLoanOriginationClient();
        EventPublisher eventPublisher = mock(EventPublisher.class);
        when(eventPublisher.publish(any(), anyString())).thenReturn(Mono.empty());

        var handler = new RegisterLoanApplicationHandler(client, eventPublisher);

        StepVerifier.create(handler.handle(new RegisterLoanApplicationCommand("Grace Hopper", 100_000L)))
                .expectNextCount(1)
                .verifyComplete();

        // The handler emits a typed domain event under the canonical event type
        // after the core write succeeds.
        var payload = ArgumentCaptor.forClass(LoanApplicationRegisteredEvent.class);
        verify(eventPublisher).publish(payload.capture(), eq(LoanApplicationRegisteredEvent.EVENT_TYPE));
        assertThat(payload.getValue().applicantName()).isEqualTo("Grace Hopper");
        assertThat(payload.getValue().amount()).isEqualTo(100_000L);
        assertThat(payload.getValue().loanApplicationId()).isEqualTo(client.createdApplications().get(0));
    }
}

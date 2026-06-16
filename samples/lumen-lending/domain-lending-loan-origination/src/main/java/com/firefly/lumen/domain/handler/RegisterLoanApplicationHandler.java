package com.firefly.lumen.domain.handler;

import com.firefly.lumen.domain.client.LoanOriginationClient;
import com.firefly.lumen.domain.command.RegisterLoanApplicationCommand;
import com.firefly.lumen.domain.event.LoanApplicationRegisteredEvent;
import org.fireflyframework.cqrs.annotations.CommandHandlerComponent;
import org.fireflyframework.cqrs.command.CommandHandler;
import org.fireflyframework.eda.publisher.EventPublisher;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Command handler for {@link RegisterLoanApplicationCommand}.
 *
 * <p>Discovered by the framework via {@code @CommandHandlerComponent} and registered on the
 * {@link org.fireflyframework.cqrs.command.CommandBus}. It extends
 * {@link CommandHandler CommandHandler&lt;RegisterLoanApplicationCommand, UUID&gt;} and
 * implements the single abstract {@code doHandle(...)} hook; the framework wraps it with
 * validation, metrics, tracing and error mapping.
 *
 * <p>The handler is the boundary to the core system of record: it calls the SDK-seam
 * {@link LoanOriginationClient} to create the application, then publishes a
 * {@link LoanApplicationRegisteredEvent} over EDA so downstream consumers can react. This
 * is the canonical "command writes, then emits a domain event" shape.
 *
 * <p>The real {@code domain-lending-loan-origination} handler injects the generated
 * {@code LoanApplicationsApi}; here we inject our trimmed {@link LoanOriginationClient} port.
 */
@CommandHandlerComponent
public class RegisterLoanApplicationHandler extends CommandHandler<RegisterLoanApplicationCommand, UUID> {

    private final LoanOriginationClient client;
    private final EventPublisher eventPublisher;

    public RegisterLoanApplicationHandler(LoanOriginationClient client, EventPublisher eventPublisher) {
        this.client = client;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected Mono<UUID> doHandle(RegisterLoanApplicationCommand command) {
        return client.createLoanApplication(command.getApplicantName(), command.getAmount())
                .flatMap(id -> publishRegistered(id, command).thenReturn(id));
    }

    private Mono<Void> publishRegistered(UUID id, RegisterLoanApplicationCommand command) {
        var event = new LoanApplicationRegisteredEvent(id, command.getApplicantName(), command.getAmount());
        return eventPublisher.publish(event, LoanApplicationRegisteredEvent.EVENT_TYPE);
    }
}

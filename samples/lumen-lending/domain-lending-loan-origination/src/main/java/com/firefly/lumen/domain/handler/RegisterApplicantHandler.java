package com.firefly.lumen.domain.handler;

import com.firefly.lumen.domain.client.LoanOriginationClient;
import com.firefly.lumen.domain.command.RegisterApplicantCommand;
import org.fireflyframework.cqrs.annotations.CommandHandlerComponent;
import org.fireflyframework.cqrs.command.CommandHandler;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Command handler for {@link RegisterApplicantCommand} (a dependent saga step).
 *
 * <p>Attaches an applicant party to the loan application the root step created, by calling
 * the SDK-seam {@link LoanOriginationClient#addApplicant(UUID, String)}.
 */
@CommandHandlerComponent
public class RegisterApplicantHandler extends CommandHandler<RegisterApplicantCommand, UUID> {

    private final LoanOriginationClient client;

    public RegisterApplicantHandler(LoanOriginationClient client) {
        this.client = client;
    }

    @Override
    protected Mono<UUID> doHandle(RegisterApplicantCommand command) {
        return client.addApplicant(command.getLoanApplicationId(), command.getApplicantName());
    }
}

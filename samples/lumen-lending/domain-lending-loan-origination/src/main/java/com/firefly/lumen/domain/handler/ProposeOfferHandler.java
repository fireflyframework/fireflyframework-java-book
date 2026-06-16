package com.firefly.lumen.domain.handler;

import com.firefly.lumen.domain.client.LoanOriginationClient;
import com.firefly.lumen.domain.command.ProposeOfferCommand;
import org.fireflyframework.cqrs.annotations.CommandHandlerComponent;
import org.fireflyframework.cqrs.command.CommandHandler;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Command handler for {@link ProposeOfferCommand} (a dependent saga step).
 *
 * <p>Records a proposed offer on the loan application the root step created, by calling the
 * SDK-seam {@link LoanOriginationClient#proposeOffer(UUID, long, int)}. The saga's
 * compensation test drives a failure <em>here</em> to prove the root step's
 * {@code removeLoanApplication} compensation fires.
 */
@CommandHandlerComponent
public class ProposeOfferHandler extends CommandHandler<ProposeOfferCommand, UUID> {

    private final LoanOriginationClient client;

    public ProposeOfferHandler(LoanOriginationClient client) {
        this.client = client;
    }

    @Override
    protected Mono<UUID> doHandle(ProposeOfferCommand command) {
        return client.proposeOffer(command.getLoanApplicationId(), command.getAmount(), command.getAnnualRateBps());
    }
}

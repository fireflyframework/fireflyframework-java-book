package com.firefly.lumen.domain.command;

import org.fireflyframework.cqrs.command.Command;

import java.util.UUID;

/**
 * CQRS command to record a proposed offer on a loan application.
 *
 * <p>A <em>dependent</em> command like {@link RegisterApplicantCommand}: the saga injects
 * the loan application id produced by the root step via {@link #withLoanApplicationId(UUID)}
 * before sending it through the {@link org.fireflyframework.cqrs.command.CommandBus}.
 */
public final class ProposeOfferCommand implements Command<UUID> {

    private final long amount;
    private final int annualRateBps;
    private UUID loanApplicationId;

    public ProposeOfferCommand(long amount, int annualRateBps) {
        this.amount = amount;
        this.annualRateBps = annualRateBps;
    }

    public ProposeOfferCommand withLoanApplicationId(UUID loanApplicationId) {
        this.loanApplicationId = loanApplicationId;
        return this;
    }

    public long getAmount() {
        return amount;
    }

    public int getAnnualRateBps() {
        return annualRateBps;
    }

    public UUID getLoanApplicationId() {
        return loanApplicationId;
    }
}

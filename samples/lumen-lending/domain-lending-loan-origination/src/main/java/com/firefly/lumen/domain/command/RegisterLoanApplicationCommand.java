package com.firefly.lumen.domain.command;

import org.fireflyframework.cqrs.command.Command;

import java.util.UUID;

/**
 * CQRS command to register a new loan application in the core system of record.
 *
 * <p>Implements {@link Command Command&lt;UUID&gt;}: the result type is the
 * server-assigned loan application id. The matching
 * {@link com.firefly.lumen.domain.handler.RegisterLoanApplicationHandler} is discovered
 * by the framework via {@code @CommandHandlerComponent} and invoked through the
 * {@link org.fireflyframework.cqrs.command.CommandBus}.
 *
 * <p>In the real {@code domain-lending-loan-origination} service this command extends the
 * generated {@code LoanApplicationDTO}; here it carries just the two fields the slice needs.
 */
public final class RegisterLoanApplicationCommand implements Command<UUID> {

    private final String applicantName;
    private final long amount;

    public RegisterLoanApplicationCommand(String applicantName, long amount) {
        this.applicantName = applicantName;
        this.amount = amount;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public long getAmount() {
        return amount;
    }
}

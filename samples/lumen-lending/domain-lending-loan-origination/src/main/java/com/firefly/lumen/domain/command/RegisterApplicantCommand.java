package com.firefly.lumen.domain.command;

import org.fireflyframework.cqrs.command.Command;

import java.util.UUID;

/**
 * CQRS command to attach an applicant party to a loan application.
 *
 * <p>This is a <em>dependent</em> command: it runs only after the root
 * {@code registerLoanApplication} step has produced the loan application id. The saga
 * injects that id via {@link #withLoanApplicationId(UUID)} before sending the command,
 * mirroring the {@code withLoanApplicationId(...)} pattern used by the real service's
 * {@code RegisterApplicationPartyCommand}.
 */
public final class RegisterApplicantCommand implements Command<UUID> {

    private final String applicantName;
    private UUID loanApplicationId;

    public RegisterApplicantCommand(String applicantName) {
        this.applicantName = applicantName;
    }

    public RegisterApplicantCommand withLoanApplicationId(UUID loanApplicationId) {
        this.loanApplicationId = loanApplicationId;
        return this;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public UUID getLoanApplicationId() {
        return loanApplicationId;
    }
}

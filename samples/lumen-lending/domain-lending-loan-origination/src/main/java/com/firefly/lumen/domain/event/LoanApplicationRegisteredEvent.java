package com.firefly.lumen.domain.event;

import java.util.UUID;

/**
 * Domain event published when a loan application has been registered in the core
 * system of record.
 *
 * <p>This is the EDA (event-driven architecture) payload. The
 * {@link com.firefly.lumen.domain.handler.RegisterLoanApplicationHandler} publishes it
 * through the Firefly {@code EventPublisher} after the core call succeeds; any number of
 * {@code @EventListener} beans (see
 * {@link com.firefly.lumen.domain.event.LoanApplicationEventRecorder}) react to it
 * asynchronously and independently.
 *
 * @param loanApplicationId the server-assigned application id
 * @param applicantName     the primary applicant's display name
 * @param amount            the requested principal, in minor units
 */
public record LoanApplicationRegisteredEvent(UUID loanApplicationId, String applicantName, long amount) {

    /** Canonical EDA event type used as both the topic and the routing/event-type key. */
    public static final String EVENT_TYPE = "loanApplication.registered";
}

package com.firefly.lumen.domain.client;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The SDK seam: the reactive port the domain (orchestration) tier calls to reach
 * the {@code core-lending-loan-origination} system of record.
 *
 * <p>In production this interface is the <em>generated core SDK</em> client. The real
 * Firefly service ({@code domain-lending-loan-origination}) injects
 * {@code com.firefly.core.lending.origination.sdk.api.LoanApplicationsApi} — a WebClient-based
 * stub generated from the core service's OpenAPI contract. We deliberately hand-roll a
 * trimmed port here so the sample compiles and tests run with <strong>no running core
 * service and no Docker</strong>; a stub implementation lives under {@code src/test/java}.
 *
 * <p>Every method is reactive ({@link Mono}) because the whole stack — command handlers,
 * saga steps, the core HTTP call — is non-blocking end to end.
 *
 * <p>Methods map one-to-one onto the saga steps that need them:
 * <ul>
 *   <li>{@link #createLoanApplication} — the root step {@code registerLoanApplication}.</li>
 *   <li>{@link #removeLoanApplication} — the root step's compensation {@code removeLoanApplication}.</li>
 *   <li>{@link #addApplicant} — the dependent step {@code registerApplicant}.</li>
 *   <li>{@link #proposeOffer} — the dependent step {@code proposeOffer}.</li>
 * </ul>
 */
public interface LoanOriginationClient {

    /**
     * Creates the loan application in the core system of record and returns its server-assigned id.
     *
     * @param applicantName the primary applicant's display name
     * @param amount        the requested principal, in minor units
     * @return the new loan application id
     */
    Mono<UUID> createLoanApplication(String applicantName, long amount);

    /**
     * Compensating action for {@link #createLoanApplication}: deletes the loan application,
     * undoing the root step when a downstream step fails.
     *
     * @param loanApplicationId the application to remove
     */
    Mono<Void> removeLoanApplication(UUID loanApplicationId);

    /**
     * Attaches an applicant party to an existing loan application (dependent step).
     *
     * @param loanApplicationId the parent application
     * @param applicantName     the party's display name
     * @return the new application-party id
     */
    Mono<UUID> addApplicant(UUID loanApplicationId, String applicantName);

    /**
     * Records a proposed offer on an existing loan application (dependent step).
     *
     * @param loanApplicationId the parent application
     * @param amount            the offered principal, in minor units
     * @param annualRateBps     the offered annual rate, in basis points
     * @return the new proposed-offer id
     */
    Mono<UUID> proposeOffer(UUID loanApplicationId, long amount, int annualRateBps);
}

package com.firefly.lumen.domain.client;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, no-infrastructure stand-in for the generated core SDK client.
 *
 * <p>This is the test-side implementation of the {@link LoanOriginationClient} SDK seam: it
 * records every call so tests can assert what the handlers and saga did, and it can be told
 * to fail a specific operation to exercise saga compensation. No HTTP, no core service, no
 * Docker.
 *
 * <p>It is intentionally <em>not</em> a Spring component — each test registers it as the
 * {@code LoanOriginationClient} bean (or constructs it directly), so the failure switches
 * stay per-test and explicit.
 */
public class StubLoanOriginationClient implements LoanOriginationClient {

    /** Ordered log of operations, e.g. {@code "create:Ada Lovelace:250000"}, {@code "remove:<uuid>"}. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<UUID> createdApplications = new CopyOnWriteArrayList<>();
    private final List<UUID> removedApplications = new CopyOnWriteArrayList<>();

    private volatile boolean failProposeOffer;
    private volatile boolean failAddApplicant;

    /** Makes {@link #proposeOffer} fail, to trigger compensation of the root step. */
    public StubLoanOriginationClient failProposeOffer() {
        this.failProposeOffer = true;
        return this;
    }

    /** Makes {@link #addApplicant} fail, to trigger compensation of the root step. */
    public StubLoanOriginationClient failAddApplicant() {
        this.failAddApplicant = true;
        return this;
    }

    @Override
    public Mono<UUID> createLoanApplication(String applicantName, long amount) {
        UUID id = UUID.randomUUID();
        calls.add("create:" + applicantName + ":" + amount);
        createdApplications.add(id);
        return Mono.just(id);
    }

    @Override
    public Mono<Void> removeLoanApplication(UUID loanApplicationId) {
        calls.add("remove:" + loanApplicationId);
        removedApplications.add(loanApplicationId);
        return Mono.empty();
    }

    @Override
    public Mono<UUID> addApplicant(UUID loanApplicationId, String applicantName) {
        if (failAddApplicant) {
            return Mono.error(new IllegalStateException("addApplicant failed (stub)"));
        }
        calls.add("addApplicant:" + loanApplicationId + ":" + applicantName);
        return Mono.just(UUID.randomUUID());
    }

    @Override
    public Mono<UUID> proposeOffer(UUID loanApplicationId, long amount, int annualRateBps) {
        if (failProposeOffer) {
            return Mono.error(new IllegalStateException("proposeOffer failed (stub)"));
        }
        calls.add("proposeOffer:" + loanApplicationId + ":" + amount + ":" + annualRateBps);
        return Mono.just(UUID.randomUUID());
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public List<UUID> createdApplications() {
        return List.copyOf(createdApplications);
    }

    public List<UUID> removedApplications() {
        return List.copyOf(removedApplications);
    }
}

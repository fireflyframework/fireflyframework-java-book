package com.firefly.lumen.exp.client;

import com.firefly.lumen.exp.dto.ApplicationDetailDTO;
import com.firefly.lumen.exp.dto.CreateApplicationRequest;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, no-infrastructure stand-in for the generated domain SDK client.
 *
 * <p>Test-side implementation of the {@link LoanOriginationDomainClient} SDK seam: it stores
 * submitted applications keyed by id (so a follow-up GET succeeds) and records every idempotency
 * key it was handed (so tests can assert the experience tier derives deterministic keys). No HTTP,
 * no domain service, no Docker.
 */
public class StubLoanOriginationDomainClient implements LoanOriginationDomainClient {

    private final Map<UUID, ApplicationDetailDTO> store = new ConcurrentHashMap<>();
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();

    @Override
    public Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey) {
        idempotencyKeys.add(idempotencyKey);
        UUID id = UUID.randomUUID();
        ApplicationDetailDTO detail = new ApplicationDetailDTO(
                id,
                request.simulationId(),
                "DRAFT",
                request.requestedAmount(),
                request.term(),
                request.purpose(),
                LocalDateTime.now(),
                LocalDateTime.now());
        store.put(id, detail);
        return Mono.just(detail);
    }

    @Override
    public Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey) {
        idempotencyKeys.add(idempotencyKey);
        ApplicationDetailDTO detail = store.get(applicationId);
        // Empty Mono models "not found" — the service maps it to a 404 problem-detail.
        return detail == null ? Mono.empty() : Mono.just(detail);
    }

    /** Ordered log of every idempotency key the experience tier handed this client. */
    public List<String> idempotencyKeys() {
        return List.copyOf(idempotencyKeys);
    }
}

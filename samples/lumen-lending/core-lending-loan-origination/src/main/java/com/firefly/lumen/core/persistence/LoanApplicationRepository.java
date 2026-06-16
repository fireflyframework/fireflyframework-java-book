package com.firefly.lumen.core.persistence;

import com.firefly.lumen.core.domain.ApplicationStatus;
import com.firefly.lumen.core.domain.LoanApplication;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive repository for {@link LoanApplication} aggregates.
 *
 * <p>The real firefly-oss repository extends an internal {@code BaseRepository}
 * (which adds pagination helpers on top of {@link ReactiveCrudRepository}); the
 * book's slice extends {@link ReactiveCrudRepository} directly to keep the
 * dependency surface small, and adds a couple of derived queries Spring Data
 * R2DBC implements from the method names.
 */
public interface LoanApplicationRepository
        extends ReactiveCrudRepository<LoanApplication, UUID> {

    /**
     * @param status lifecycle state to match
     * @return every application currently in {@code status}
     */
    Flux<LoanApplication> findByStatus(ApplicationStatus status);

    /**
     * @param applicationNumber the stable public reference
     * @return the matching application, if any
     */
    Mono<LoanApplication> findByApplicationNumber(UUID applicationNumber);
}

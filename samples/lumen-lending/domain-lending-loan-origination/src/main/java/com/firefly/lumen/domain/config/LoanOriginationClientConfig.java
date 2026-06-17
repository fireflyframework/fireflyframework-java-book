package com.firefly.lumen.domain.config;

import com.firefly.lumen.domain.client.LoanOriginationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Runtime wiring for the core {@link LoanOriginationClient} SDK seam.
 *
 * <p>The command handlers and the saga inject {@link LoanOriginationClient}. In production this
 * bean is the <em>generated core SDK</em> client (a WebClient-backed
 * {@code com.firefly.core.lending.origination.sdk.api.LoanApplicationsApi}). The book sample has
 * no generated SDK on its classpath, and the trimmed core controller exposes only a subset of the
 * operations the saga needs, so here we provide a faithful <strong>in-JVM</strong> adapter: it
 * satisfies the seam so the domain tier boots and serves standalone (no running core service, no
 * Docker), and lets the in-process saga run end to end. It mirrors the behaviour of the test-side
 * {@code StubLoanOriginationClient}, minus the test-only assertion hooks.
 *
 * <p>Registered as an {@link AutoConfiguration} (via {@code META-INF/spring/
 * org.springframework.boot.autoconfigure.AutoConfiguration.imports}) so it is processed
 * <em>after</em> user and test configuration. Combined with {@code @ConditionalOnMissingBean},
 * this keeps the slice tests in control: each {@code @SpringBootTest} registers its own
 * {@code LoanOriginationClient} (the recording stub) via {@code @TestConfiguration}, which then
 * takes precedence and this default backs off — so adding this bean does not change any test
 * behaviour.
 */
@AutoConfiguration
public class LoanOriginationClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LoanOriginationClientConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public LoanOriginationClient inJvmLoanOriginationClient() {
        log.info("Wiring in-JVM LoanOriginationClient (no generated core SDK on classpath); "
                + "the saga runs in-process against this seam");
        return new InJvmLoanOriginationClient();
    }

    /**
     * In-process implementation of the core SDK seam. Generates server-style ids and is a no-op
     * for compensation, so the orchestration tier is fully exercisable without a running core
     * service. Faithful to the sample's "everything in-JVM" stance.
     */
    static final class InJvmLoanOriginationClient implements LoanOriginationClient {

        @Override
        public Mono<UUID> createLoanApplication(String applicantName, long amount) {
            return Mono.fromSupplier(UUID::randomUUID);
        }

        @Override
        public Mono<Void> removeLoanApplication(UUID loanApplicationId) {
            return Mono.empty();
        }

        @Override
        public Mono<UUID> addApplicant(UUID loanApplicationId, String applicantName) {
            return Mono.fromSupplier(UUID::randomUUID);
        }

        @Override
        public Mono<UUID> proposeOffer(UUID loanApplicationId, long amount, int annualRateBps) {
            return Mono.fromSupplier(UUID::randomUUID);
        }
    }
}

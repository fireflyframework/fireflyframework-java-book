package com.firefly.lumen.domain.saga;

import com.firefly.lumen.domain.client.LoanOriginationClient;
import com.firefly.lumen.domain.client.StubLoanOriginationClient;
import com.firefly.lumen.domain.service.LoanOriginationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compensation (rollback) integration test for {@link RegisterApplicationSaga} — the
 * headline teaching example for the saga chapter.
 *
 * <p>The SDK-seam stub is configured so the dependent {@code proposeOffer} step fails. Because
 * that step {@code dependsOn} the root {@code registerLoanApplication}, the engine must
 * <em>compensate</em> the already-completed root step by invoking its {@code compensate}
 * method, {@code removeLoanApplication}. The test proves the application that was created is
 * subsequently removed — the saga left no orphaned write behind.
 */
@SpringBootTest
@Import(RegisterApplicationSagaCompensationTest.FailingOfferConfig.class)
class RegisterApplicationSagaCompensationTest {

    @TestConfiguration
    static class FailingOfferConfig {
        @Bean
        LoanOriginationClient loanOriginationClient() {
            return new StubLoanOriginationClient().failProposeOffer();
        }
    }

    @Autowired
    private LoanOriginationService service;

    @Autowired
    private LoanOriginationClient client;

    @Test
    void submitApplication_failsAndCompensatesRootStep_whenDependentStepThrows() {
        StepVerifier.create(service.submitApplication("Ada Lovelace", 250_000L, 575))
                .assertNext(result -> {
                    // The saga as a whole failed.
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(result.isFailed()).isTrue();
                    // The failing step is the proposeOffer dependent step.
                    assertThat(result.failedSteps()).contains(RegisterApplicationSaga.STEP_PROPOSE_OFFER);
                    // The root step was compensated (its removeLoanApplication ran).
                    assertThat(result.compensatedSteps())
                            .contains(RegisterApplicationSaga.STEP_REGISTER_LOAN_APPLICATION);
                })
                .verifyComplete();

        var stub = (StubLoanOriginationClient) client;
        // The application was created by the root step...
        assertThat(stub.createdApplications()).hasSize(1);
        // ...and then removed by the root step's compensation — same id, no orphan.
        assertThat(stub.removedApplications()).containsExactlyElementsOf(stub.createdApplications());
    }
}

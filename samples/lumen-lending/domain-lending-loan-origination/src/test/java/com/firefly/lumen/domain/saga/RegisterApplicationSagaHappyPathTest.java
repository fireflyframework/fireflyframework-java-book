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
 * Happy-path integration test for {@link RegisterApplicationSaga}.
 *
 * <p>Boots a real Spring context so the framework wires the {@code SagaEngine}, the
 * {@code CommandBus}, every {@code @CommandHandlerComponent}, and the {@code @Saga} bean.
 * The only substitution is the SDK seam: the {@link LoanOriginationClient} bean is the
 * in-memory {@link StubLoanOriginationClient}. No Docker, no core service.
 *
 * <p>Asserts the saga completes successfully and that all three steps ran (the root create
 * plus the two dependent operations), observed through the stub's call log.
 */
@SpringBootTest
@Import(RegisterApplicationSagaHappyPathTest.StubConfig.class)
class RegisterApplicationSagaHappyPathTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        LoanOriginationClient loanOriginationClient() {
            return new StubLoanOriginationClient();
        }
    }

    @Autowired
    private LoanOriginationService service;

    @Autowired
    private LoanOriginationClient client;

    @Test
    void submitApplication_completesSaga_andRunsAllSteps() {
        StepVerifier.create(service.submitApplication("Ada Lovelace", 250_000L, 575))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.compensatedSteps()).isEmpty();
                    assertThat(result.steps()).containsKeys(
                            RegisterApplicationSaga.STEP_REGISTER_LOAN_APPLICATION,
                            RegisterApplicationSaga.STEP_REGISTER_APPLICANT,
                            RegisterApplicationSaga.STEP_PROPOSE_OFFER);
                })
                .verifyComplete();

        var stub = (StubLoanOriginationClient) client;
        // Root step created exactly one application; nothing was rolled back.
        assertThat(stub.createdApplications()).hasSize(1);
        assertThat(stub.removedApplications()).isEmpty();
        // Both dependent steps reached the core seam.
        assertThat(stub.calls()).anyMatch(c -> c.startsWith("addApplicant:"));
        assertThat(stub.calls()).anyMatch(c -> c.startsWith("proposeOffer:"));
    }
}

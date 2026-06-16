package com.firefly.lumen.domain.service;

import com.firefly.lumen.domain.command.ProposeOfferCommand;
import com.firefly.lumen.domain.command.RegisterApplicantCommand;
import com.firefly.lumen.domain.command.RegisterLoanApplicationCommand;
import com.firefly.lumen.domain.query.GetApplicationStatusQuery;
import com.firefly.lumen.domain.saga.RegisterApplicationSaga;
import org.fireflyframework.cqrs.query.QueryBus;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Domain service that drives loan-origination flows.
 *
 * <p>It injects the {@link SagaEngine} (for the multi-step write flow) and the
 * {@link QueryBus} (for the read flow), exactly as the real service does. {@code
 * submitApplication(...)} assembles per-step inputs with {@link StepInputs} — one entry per
 * {@code @SagaStep} id — and runs the saga by name, returning the {@link SagaResult} so the
 * caller can inspect success, failed steps and compensations.
 */
@Service
public class LoanOriginationService {

    private final SagaEngine sagaEngine;
    private final QueryBus queryBus;

    public LoanOriginationService(SagaEngine sagaEngine, QueryBus queryBus) {
        this.sagaEngine = sagaEngine;
        this.queryBus = queryBus;
    }

    /**
     * Runs the {@code RegisterApplicationSaga}: registers the application (root step) and,
     * once its id is known, attaches the applicant and proposes an offer (dependent steps).
     *
     * @param applicantName primary applicant name
     * @param amount        requested principal, in minor units
     * @param annualRateBps offered annual rate, in basis points
     * @return the saga result (success/failure plus per-step outcomes)
     */
    public Mono<SagaResult> submitApplication(String applicantName, long amount, int annualRateBps) {
        StepInputs inputs = StepInputs.builder()
                .forStepId(RegisterApplicationSaga.STEP_REGISTER_LOAN_APPLICATION,
                        new RegisterLoanApplicationCommand(applicantName, amount))
                .forStepId(RegisterApplicationSaga.STEP_REGISTER_APPLICANT,
                        new RegisterApplicantCommand(applicantName))
                .forStepId(RegisterApplicationSaga.STEP_PROPOSE_OFFER,
                        new ProposeOfferCommand(amount, annualRateBps))
                .build();

        return sagaEngine.execute(RegisterApplicationSaga.SAGA_NAME, inputs);
    }

    /** Reads the current status of a loan application via the {@link QueryBus}. */
    public Mono<String> getApplicationStatus(UUID loanApplicationId) {
        return queryBus.query(new GetApplicationStatusQuery(loanApplicationId));
    }
}

package com.firefly.lumen.domain.saga;

import com.firefly.lumen.domain.command.ProposeOfferCommand;
import com.firefly.lumen.domain.command.RegisterApplicantCommand;
import com.firefly.lumen.domain.command.RegisterLoanApplicationCommand;
import com.firefly.lumen.domain.client.LoanOriginationClient;
import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.saga.annotation.Saga;
import org.fireflyframework.orchestration.saga.annotation.SagaStep;
import org.fireflyframework.orchestration.saga.annotation.StepEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Saga that orchestrates loan-application registration across the core system of record.
 *
 * <p>This is the orchestration tier's centrepiece and a faithful trim of the real
 * {@code domain-lending-loan-origination} {@code RegisterApplicationSaga}. The
 * {@code @Saga(name = ...)} registers it with the {@link org.fireflyframework.orchestration.saga.engine.SagaEngine},
 * which runs it by name via {@code execute("RegisterApplicationSaga", inputs)}.
 *
 * <p>Topology (a forward dependency DAG):
 * <pre>
 *   registerLoanApplication            (root; compensate = removeLoanApplication)
 *     ├── registerApplicant            (dependsOn root)
 *     └── proposeOffer                 (dependsOn root)
 * </pre>
 *
 * <p>The root step publishes the new loan application id into the {@link ExecutionContext}
 * under {@link #CTX_LOAN_APPLICATION_ID}; the dependent steps read it back via
 * {@code ctx.getVariableAs(...)} and stamp it onto their commands before dispatch. Each step
 * carries a {@code @StepEvent} so the engine emits a step-level domain event. If any step
 * fails, the engine runs the matching {@code compensate} methods of the steps that already
 * completed — the heart of the saga pattern.
 *
 * <p>Every step delegates to a CQRS command via the {@link CommandBus}; the command handlers
 * call the {@link LoanOriginationClient} SDK seam. The compensation for the root step calls
 * the client directly (the inverse "delete" operation).
 */
@Saga(name = RegisterApplicationSaga.SAGA_NAME)
@Service
public class RegisterApplicationSaga {

    /** Saga name; pass this to {@code SagaEngine.execute(name, inputs)}. */
    public static final String SAGA_NAME = "RegisterApplicationSaga";

    /** Step ids — also the keys used in {@code StepInputs.forStepId(...)}. */
    public static final String STEP_REGISTER_LOAN_APPLICATION = "registerLoanApplication";
    public static final String STEP_REGISTER_APPLICANT = "registerApplicant";
    public static final String STEP_PROPOSE_OFFER = "proposeOffer";

    /** Compensation method names (referenced by {@code @SagaStep(compensate = ...)}). */
    public static final String COMPENSATE_REMOVE_LOAN_APPLICATION = "removeLoanApplication";
    public static final String COMPENSATE_REMOVE_APPLICANT = "removeApplicant";
    public static final String COMPENSATE_REMOVE_OFFER = "removeOffer";

    /** Step-event types emitted by each step. */
    public static final String EVENT_LOAN_APPLICATION_REGISTERED = "loanApplication.registered";
    public static final String EVENT_APPLICANT_REGISTERED = "applicant.registered";
    public static final String EVENT_OFFER_PROPOSED = "offer.proposed";

    /** Execution-context key under which the root step publishes the new application id. */
    public static final String CTX_LOAN_APPLICATION_ID = "loanApplicationId";

    private final CommandBus commandBus;
    private final LoanOriginationClient client;

    public RegisterApplicationSaga(CommandBus commandBus, LoanOriginationClient client) {
        this.commandBus = commandBus;
        this.client = client;
    }

    // ---------------------------------------------------------------------
    // Root step: create the loan application, publish its id into the context.
    // ---------------------------------------------------------------------

    @SagaStep(id = STEP_REGISTER_LOAN_APPLICATION, compensate = COMPENSATE_REMOVE_LOAN_APPLICATION)
    @StepEvent(type = EVENT_LOAN_APPLICATION_REGISTERED)
    public Mono<UUID> registerLoanApplication(RegisterLoanApplicationCommand command, ExecutionContext ctx) {
        return commandBus.send(command)
                .doOnNext(loanApplicationId -> ctx.putVariable(CTX_LOAN_APPLICATION_ID, loanApplicationId));
    }

    /** Compensation for the root step: delete the application created above. */
    public Mono<Void> removeLoanApplication(UUID loanApplicationId) {
        return client.removeLoanApplication(loanApplicationId);
    }

    // ---------------------------------------------------------------------
    // Dependent step: attach the applicant party (after the root step).
    // ---------------------------------------------------------------------

    @SagaStep(id = STEP_REGISTER_APPLICANT,
            compensate = COMPENSATE_REMOVE_APPLICANT,
            dependsOn = STEP_REGISTER_LOAN_APPLICATION)
    @StepEvent(type = EVENT_APPLICANT_REGISTERED)
    public Mono<UUID> registerApplicant(RegisterApplicantCommand command, ExecutionContext ctx) {
        UUID loanApplicationId = ctx.getVariableAs(CTX_LOAN_APPLICATION_ID, UUID.class);
        return commandBus.send(command.withLoanApplicationId(loanApplicationId));
    }

    /** Compensation for {@link #registerApplicant}. No-op in this slice; nothing to undo upstream. */
    public Mono<Void> removeApplicant(UUID applicantId, ExecutionContext ctx) {
        return Mono.empty();
    }

    // ---------------------------------------------------------------------
    // Dependent step: propose an offer (after the root step).
    // ---------------------------------------------------------------------

    @SagaStep(id = STEP_PROPOSE_OFFER,
            compensate = COMPENSATE_REMOVE_OFFER,
            dependsOn = STEP_REGISTER_LOAN_APPLICATION)
    @StepEvent(type = EVENT_OFFER_PROPOSED)
    public Mono<UUID> proposeOffer(ProposeOfferCommand command, ExecutionContext ctx) {
        UUID loanApplicationId = ctx.getVariableAs(CTX_LOAN_APPLICATION_ID, UUID.class);
        return commandBus.send(command.withLoanApplicationId(loanApplicationId));
    }

    /** Compensation for {@link #proposeOffer}. No-op in this slice; nothing to undo upstream. */
    public Mono<Void> removeOffer(UUID offerId, ExecutionContext ctx) {
        return Mono.empty();
    }
}

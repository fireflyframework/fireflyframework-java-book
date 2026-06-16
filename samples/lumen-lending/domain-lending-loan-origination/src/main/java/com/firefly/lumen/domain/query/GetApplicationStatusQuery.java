package com.firefly.lumen.domain.query;

import org.fireflyframework.cqrs.query.Query;

import java.util.UUID;

/**
 * CQRS query to read the current status of a loan application.
 *
 * <p>Implements {@link Query Query&lt;String&gt;}: the result is the status label. Read paths
 * use the {@link org.fireflyframework.cqrs.query.QueryBus}; write paths use the
 * {@link org.fireflyframework.cqrs.command.CommandBus}. Keeping them separate is the
 * "CQRS" in the Firefly framework name.
 */
public final class GetApplicationStatusQuery implements Query<String> {

    private final UUID loanApplicationId;

    public GetApplicationStatusQuery(UUID loanApplicationId) {
        this.loanApplicationId = loanApplicationId;
    }

    public UUID getLoanApplicationId() {
        return loanApplicationId;
    }
}

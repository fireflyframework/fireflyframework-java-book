package com.firefly.lumen.domain.handler;

import com.firefly.lumen.domain.query.GetApplicationStatusQuery;
import org.fireflyframework.cqrs.annotations.QueryHandlerComponent;
import org.fireflyframework.cqrs.query.QueryHandler;
import reactor.core.publisher.Mono;

/**
 * Query handler for {@link GetApplicationStatusQuery}.
 *
 * <p>The read-side counterpart to the command handlers: discovered via
 * {@code @QueryHandlerComponent} and dispatched through the
 * {@link org.fireflyframework.cqrs.query.QueryBus}. The slice keeps the read model trivial —
 * once an application id exists it is reported as {@code REGISTERED} — to demonstrate the
 * command/query split without pulling in a projection store.
 */
@QueryHandlerComponent
public class GetApplicationStatusHandler extends QueryHandler<GetApplicationStatusQuery, String> {

    @Override
    protected Mono<String> doHandle(GetApplicationStatusQuery query) {
        return Mono.just("REGISTERED");
    }
}

package com.firefly.lumen.domain.event;

import org.fireflyframework.eda.annotation.EventListener;
import org.fireflyframework.eda.annotation.PublisherType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * EDA consumer: records every {@link LoanApplicationRegisteredEvent} it receives.
 *
 * <p>The {@code @EventListener} annotation registers this method with the Firefly EDA
 * runtime. We bind it to the in-JVM {@link PublisherType#APPLICATION_EVENT} transport so
 * the sample runs with <strong>no Kafka and no Docker</strong> — events are delivered over
 * Spring's {@code ApplicationEventPublisher}. The {@code eventTypes} filter is the payload's
 * simple class name: the EDA runtime keys both the producer's payload and this listener by
 * {@code LoanApplicationRegisteredEvent}, so they match in-process.
 *
 * <p>The recorded list is the EDA chapter's assertion hook: tests publish (directly or via
 * the handler) and verify the listener fired, demonstrating producer/consumer decoupling.
 */
@Component
public class LoanApplicationEventRecorder {

    private final List<LoanApplicationRegisteredEvent> received = new CopyOnWriteArrayList<>();

    @EventListener(
            destinations = "LoanApplicationRegisteredEvent",
            eventTypes = "LoanApplicationRegisteredEvent",
            consumerType = PublisherType.APPLICATION_EVENT)
    public void on(LoanApplicationRegisteredEvent event) {
        received.add(event);
    }

    /** Returns an immutable snapshot of the events received so far. */
    public List<LoanApplicationRegisteredEvent> received() {
        return List.copyOf(received);
    }
}

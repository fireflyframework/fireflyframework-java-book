package com.firefly.lumen.core;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

/**
 * Self-contained tour of Reactor's {@link Mono}/{@link Flux} verified with
 * {@code reactor-test}'s {@link StepVerifier}. These listings become the
 * worked examples in the book's Chapter 5 ("The Reactive Model").
 *
 * <p>Each test asserts the exact sequence of signals a publisher emits, which is
 * how reactive code is tested without blocking.
 */
class ReactiveModelTest {

    @Test
    void monoEmitsOneValueThenCompletes() {
        Mono<String> greeting = Mono.just("hello");

        StepVerifier.create(greeting)
                .expectNext("hello")
                .verifyComplete();
    }

    @Test
    void emptyMonoCompletesWithoutAValue() {
        StepVerifier.create(Mono.empty())
                .verifyComplete();
    }

    @Test
    void fluxEmitsEachElementInOrder() {
        Flux<Integer> numbers = Flux.just(1, 2, 3);

        StepVerifier.create(numbers)
                .expectNext(1, 2, 3)
                .verifyComplete();
    }

    @Test
    void operatorsTransformTheStream() {
        Flux<Integer> evensDoubled = Flux.range(1, 6)
                .filter(n -> n % 2 == 0)
                .map(n -> n * 10);

        StepVerifier.create(evensDoubled)
                .expectNext(20, 40, 60)
                .verifyComplete();
    }

    @Test
    void errorsArePropagatedAsTerminalSignals() {
        Flux<Integer> failing = Flux.just(1, 2)
                .concatWith(Flux.error(new IllegalStateException("boom")));

        StepVerifier.create(failing)
                .expectNext(1, 2)
                .expectErrorMatches(e -> e instanceof IllegalStateException
                        && "boom".equals(e.getMessage()))
                .verify();
    }

    @Test
    void virtualTimeProvesDelayWithoutWaiting() {
        StepVerifier.withVirtualTime(() -> Mono.just("done").delayElement(Duration.ofHours(1)))
                .expectSubscription()
                .thenAwait(Duration.ofHours(1))
                .expectNext("done")
                .verifyComplete();
    }
}

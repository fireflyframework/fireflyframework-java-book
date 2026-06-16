package com.firefly.lumen.exp.service;

import com.firefly.lumen.exp.client.StubLoanOriginationDomainClient;
import com.firefly.lumen.exp.dto.ApplicationDetailDTO;
import com.firefly.lumen.exp.dto.CreateApplicationRequest;
import com.firefly.lumen.exp.util.IdempotencyKeys;
import org.fireflyframework.web.error.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast unit tests for {@link ApplicationService}: validation, deterministic idempotency keys, and
 * downstream-failure mapping. Uses the in-memory {@link StubLoanOriginationDomainClient}; no Spring
 * context, no Docker.
 */
class ApplicationServiceTest {

    private final StubLoanOriginationDomainClient stub = new StubLoanOriginationDomainClient();
    private final ApplicationService service = new ApplicationService(stub);

    @Test
    void createApplication_derivesDeterministicIdempotencyKey() {
        var productId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        var simulationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var request = new CreateApplicationRequest(
                productId, new BigDecimal("15000"), 36, "PERSONAL", simulationId);

        String expectedKey = IdempotencyKeys.of(
                "exp-lending", "create-application", "submit",
                productId.toString(), simulationId.toString(),
                "15000", "36", "PERSONAL");

        StepVerifier.create(service.createApplication(request))
                .assertNext(detail -> assertThat(detail.requestedAmount()).isEqualByComparingTo("15000"))
                .verifyComplete();

        // Same logical request -> same key the service handed the SDK seam.
        assertThat(stub.idempotencyKeys()).containsExactly(expectedKey);
    }

    @Test
    void createApplication_rejectsNonPositiveAmount() {
        var request = new CreateApplicationRequest(
                UUID.randomUUID(), BigDecimal.ZERO, 36, "PERSONAL", null);

        StepVerifier.create(service.createApplication(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                })
                .verify();
    }

    @Test
    void getApplication_mapsMissingToNotFound() {
        StepVerifier.create(service.getApplication(UUID.randomUUID()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BusinessException.class);
                    var be = (BusinessException) error;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(be.getCode()).isEqualTo("APPLICATION_NOT_FOUND");
                })
                .verify();
    }

    @Test
    void createThenGet_roundTrips() {
        var request = new CreateApplicationRequest(
                UUID.randomUUID(), new BigDecimal("20000"), 48, "RENOVATION", null);

        ApplicationDetailDTO created = service.createApplication(request).block();
        assertThat(created).isNotNull();

        StepVerifier.create(service.getApplication(created.applicationId()))
                .assertNext(detail -> {
                    assertThat(detail.applicationId()).isEqualTo(created.applicationId());
                    assertThat(detail.term()).isEqualTo(48);
                })
                .verifyComplete();
    }
}

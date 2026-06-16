package com.firefly.lumen.core.web;

import com.firefly.lumen.core.dto.CreateLoanApplicationRequest;
import com.firefly.lumen.core.dto.LoanApplicationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end slice test: boots the full reactive context against in-memory H2
 * (R2DBC runtime + Flyway migration, no Docker) and drives the REST API with
 * {@link WebTestClient}.
 *
 * <p>Covers the create -> read round trip, the RFC 7807 404, and bean-validation
 * rejection of a bad payload. This is the verified source for the book's
 * web-layer chapter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanApplicationControllerTest {

    @Autowired
    private WebTestClient client;

    private CreateLoanApplicationRequest validRequest() {
        return new CreateLoanApplicationRequest(
                UUID.randomUUID(),
                new BigDecimal("12500.00"),
                "EUR",
                36,
                "HOME_IMPROVEMENT");
    }

    @Test
    void createsAnApplicationAndReadsItBack() {
        LoanApplicationResponse created = client.post()
                .uri("/api/v1/loan-applications")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(LoanApplicationResponse.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(created);
        assertNotNull(created.loanApplicationId());
        // The service submits the application as part of creation.
        assertEquals("SUBMITTED", created.status().name());
        assertEquals(new BigDecimal("12500.00"), created.requestedAmount());

        client.get()
                .uri("/api/v1/loan-applications/{id}", created.loanApplicationId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.loanApplicationId").isEqualTo(created.loanApplicationId().toString())
                .jsonPath("$.currency").isEqualTo("EUR")
                .jsonPath("$.purpose").isEqualTo("HOME_IMPROVEMENT");
    }

    @Test
    void returnsRfc7807ProblemDetailWhenMissing() {
        client.get()
                .uri("/api/v1/loan-applications/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void rejectsAnInvalidPayload() {
        CreateLoanApplicationRequest bad = new CreateLoanApplicationRequest(
                UUID.randomUUID(),
                new BigDecimal("-5.00"),  // fails @ValidAmount
                "EUR",
                36,
                "HOME_IMPROVEMENT");

        client.post()
                .uri("/api/v1/loan-applications")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bad)
                .exchange()
                .expectStatus().isBadRequest();
    }
}

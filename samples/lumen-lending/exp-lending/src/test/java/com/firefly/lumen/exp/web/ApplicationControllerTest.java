package com.firefly.lumen.exp.web;

import com.firefly.lumen.exp.client.LoanOriginationDomainClient;
import com.firefly.lumen.exp.client.StubLoanOriginationDomainClient;
import com.firefly.lumen.exp.dto.ApplicationDetailDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context slice for the experience (BFF) loan-origination tier.
 *
 * <p>Boots the real {@code exp-lending} application (controllers, service, security filter chain,
 * fireflyframework-web's GlobalExceptionHandler) and drives it through {@link WebTestClient}. The
 * SDK seam is satisfied by an in-memory {@link StubLoanOriginationDomainClient} bean — no domain
 * service, no Docker. The controllers keep their real {@code @Secure} annotations; enforcement is
 * disabled via {@code firefly.application.security.enabled=false} in {@code src/test/resources/application.yml}.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class ApplicationControllerTest {

    private static final String BASE_PATH = "/api/v1/experience/lending/applications";

    @TestConfiguration
    static class StubConfig {
        /** Registers the in-memory stub as the SDK-seam bean; wins via the config's @ConditionalOnMissingBean. */
        @Bean
        LoanOriginationDomainClient loanOriginationDomainClient() {
            return new StubLoanOriginationDomainClient();
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void createApplication_returns201WithMappedDetail() {
        webTestClient.post()
                .uri(BASE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                            "productId": "550e8400-e29b-41d4-a716-446655440000",
                            "requestedAmount": 15000,
                            "term": 36,
                            "purpose": "PERSONAL",
                            "simulationId": "11111111-1111-1111-1111-111111111111"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ApplicationDetailDTO.class)
                .value(body -> {
                    assertThat(body.applicationId()).isNotNull();
                    assertThat(body.requestedAmount()).isEqualByComparingTo("15000");
                    assertThat(body.term()).isEqualTo(36);
                    assertThat(body.purpose()).isEqualTo("PERSONAL");
                    assertThat(body.status()).isEqualTo("DRAFT");
                    assertThat(body.simulationId())
                            .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
                });
    }

    @Test
    void createThenGetApplication_roundTrips() {
        var created = webTestClient.post()
                .uri(BASE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                            "productId": "550e8400-e29b-41d4-a716-446655440000",
                            "requestedAmount": 20000,
                            "term": 48,
                            "purpose": "RENOVATION"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ApplicationDetailDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        UUID applicationId = created.applicationId();

        webTestClient.get()
                .uri(BASE_PATH + "/{id}", applicationId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ApplicationDetailDTO.class)
                .value(body -> {
                    assertThat(body.applicationId()).isEqualTo(applicationId);
                    assertThat(body.requestedAmount()).isEqualByComparingTo("20000");
                    assertThat(body.term()).isEqualTo(48);
                });
    }

    @Test
    void getUnknownApplication_returnsProblemDetailNotFound() {
        UUID unknownId = UUID.randomUUID();

        webTestClient.get()
                .uri(BASE_PATH + "/{id}", unknownId)
                .exchange()
                // BusinessException(NOT_FOUND, ...) is rendered as a problem-detail by
                // fireflyframework-web's GlobalExceptionHandler.
                .expectStatus().isNotFound()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).isNotBlank();
                    assertThat(body).contains("APPLICATION_NOT_FOUND");
                });
    }

    @Test
    void createApplication_rejectsInvalidAmountWithBadRequest() {
        webTestClient.post()
                .uri(BASE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                            "productId": "550e8400-e29b-41d4-a716-446655440000",
                            "requestedAmount": 0,
                            "term": 36
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }
}

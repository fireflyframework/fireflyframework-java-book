package com.firefly.lumen.domain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the core Loan Origination service the domain (orchestration) tier
 * calls over HTTP.
 *
 * <p>Mirrors the real {@code domain-lending-loan-origination} core-client properties: it binds the
 * base URL and timeout of the core system of record. The book sample uses the
 * {@code firefly.lumen.core.loan-origination} prefix so chapters can show it without the full
 * production config tree. When {@code base-path} is set, the live
 * {@link com.firefly.lumen.domain.client.WebClientLoanOriginationClient} activates and the saga's
 * root step writes to core over HTTP.
 *
 * @param basePath base URL of the core Loan Origination service (e.g. {@code http://localhost:8081})
 * @param timeout  read/connect timeout for SDK calls; defaults to 10 seconds
 */
@ConfigurationProperties(prefix = "firefly.lumen.core.loan-origination")
public record CoreLoanOriginationProperties(
        String basePath,
        Duration timeout
) {

    public CoreLoanOriginationProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }
}

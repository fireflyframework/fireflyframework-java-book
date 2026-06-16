package com.firefly.lumen.exp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the domain-tier Loan Origination API the experience service calls.
 *
 * <p>Mirrors the real {@code exp-lending} {@code LoanOriginationProperties}: it binds the base URL
 * and timeout of the domain service. The real service binds under
 * {@code api-configuration.domain-platform.lending-loan-origination}; the book sample uses the
 * shorter {@code lumen.exp.loan-origination} prefix so chapters can show it without the full
 * production config tree.
 *
 * @param basePath base URL of the domain Loan Origination service (e.g. {@code http://localhost:8082})
 * @param timeout  read/connect timeout for SDK calls; defaults to 10 seconds
 */
@ConfigurationProperties(prefix = "lumen.exp.loan-origination")
public record LoanOriginationClientProperties(
        String basePath,
        Duration timeout
) {

    public LoanOriginationClientProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(10);
        }
    }
}

package com.firefly.lumen.exp.config;

import com.firefly.lumen.exp.client.LoanOriginationDomainClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Production wiring for the domain Loan Origination SDK seam (Chapter 16 material).
 *
 * <p>Mirrors the real {@code exp-lending} {@code LoanOriginationClientFactory}: it builds a
 * {@link WebClient} pointed at the configured base path and exposes a
 * {@link LoanOriginationDomainClient} bean. In the real service the bean wraps the generated
 * {@code com.firefly.domain.lending.loan.origination.sdk.api.LoanOriginationApi}; the book sample
 * has no generated SDK on its classpath, so this configuration is intentionally inert unless a
 * base path is configured.
 *
 * <ul>
 *   <li>{@code @ConditionalOnProperty(lumen.exp.loan-origination.base-path)} — the production bean
 *       only materialises when an operator points the experience tier at a real domain service.</li>
 *   <li>{@code @ConditionalOnMissingBean} — tests register an in-memory
 *       {@link LoanOriginationDomainClient} stub, which then takes precedence.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(LoanOriginationClientProperties.class)
public class LoanOriginationClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LoanOriginationClientConfig.class);
    private static final int MAX_IN_MEMORY_SIZE = 20 * 1024 * 1024;

    /**
     * Builds the {@link WebClient} the domain client adapter uses. Mirrors the codec sizing of the
     * real {@code LoanOriginationClientFactory}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public WebClient loanOriginationWebClient(LoanOriginationClientProperties properties) {
        log.info("Building Loan Origination WebClient basePath={} timeout={}",
                properties.basePath(), properties.timeout());
        return WebClient.builder()
                .baseUrl(properties.basePath())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }

    /**
     * Production {@link LoanOriginationDomainClient}. In the real service this wraps the generated
     * domain SDK {@code LoanOriginationApi}; here it is a {@link WebClient}-backed adapter so the
     * wiring is faithful and chapters can slice it verbatim. Only created when a base path is set
     * and no other client bean (e.g. a test stub) is present.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lumen.exp.loan-origination", name = "base-path")
    @ConditionalOnMissingBean
    public LoanOriginationDomainClient loanOriginationDomainClient(WebClient loanOriginationWebClient) {
        return new WebClientLoanOriginationDomainClient(loanOriginationWebClient);
    }
}

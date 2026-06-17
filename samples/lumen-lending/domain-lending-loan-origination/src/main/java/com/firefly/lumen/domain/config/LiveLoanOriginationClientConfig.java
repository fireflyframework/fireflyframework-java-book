package com.firefly.lumen.domain.config;

import com.firefly.lumen.domain.client.LoanOriginationClient;
import com.firefly.lumen.domain.client.WebClientLoanOriginationClient;
import com.firefly.lumen.domain.web.CoreLoanApplicationReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Live wiring for the core Loan Origination SDK seam.
 *
 * <p>When an operator points the domain tier at a real core service via
 * {@code firefly.lumen.core.loan-origination.base-path}, this configuration builds a
 * {@link WebClient}-backed {@link WebClientLoanOriginationClient} and registers it as both the
 * write-side {@link LoanOriginationClient} (used by the command handlers and the saga) and the
 * read-side {@link CoreLoanApplicationReader} (used by the domain web layer's GET-by-id path). The
 * saga's root {@code registerLoanApplication} step then writes to core over HTTP, and its
 * compensation deletes over HTTP.
 *
 * <ul>
 *   <li>{@code @ConditionalOnProperty(firefly.lumen.core.loan-origination.base-path)} — the live
 *       client only materialises when a core base path is configured. The shipped main
 *       {@code application.yml} sets it to {@code http://localhost:8081}, so the standalone domain
 *       app calls the running core service.</li>
 *   <li>{@code @ConditionalOnMissingBean} — the slice tests register their own
 *       {@link LoanOriginationClient} stub, which takes precedence, so this client never displaces
 *       the test seam.</li>
 * </ul>
 *
 * <p>This is a plain user {@code @Configuration} (not an auto-configuration), so it is processed
 * before the {@link com.firefly.lumen.domain.client.LoanOriginationClientConfig} auto-config; the
 * in-JVM default there backs off whenever this live client is present (its base-path is set).
 */
@Configuration
@EnableConfigurationProperties(CoreLoanOriginationProperties.class)
public class LiveLoanOriginationClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LiveLoanOriginationClientConfig.class);
    private static final int MAX_IN_MEMORY_SIZE = 20 * 1024 * 1024;

    @Bean
    @ConditionalOnProperty(prefix = "firefly.lumen.core.loan-origination", name = "base-path")
    @ConditionalOnMissingBean(name = "coreLoanOriginationWebClient")
    public WebClient coreLoanOriginationWebClient(CoreLoanOriginationProperties properties) {
        log.info("Building core Loan Origination WebClient basePath={} timeout={}",
                properties.basePath(), properties.timeout());
        return WebClient.builder()
                .baseUrl(properties.basePath())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }

    /**
     * The live core client, registered for both the write seam ({@link LoanOriginationClient}) and
     * the read seam ({@link CoreLoanApplicationReader}). Only created when a base path is set and no
     * other {@link LoanOriginationClient} (e.g. a test stub) is present.
     */
    @Bean
    @ConditionalOnProperty(prefix = "firefly.lumen.core.loan-origination", name = "base-path")
    @ConditionalOnMissingBean(LoanOriginationClient.class)
    public WebClientLoanOriginationClient webClientLoanOriginationClient(WebClient coreLoanOriginationWebClient) {
        log.info("Wiring live WebClient LoanOriginationClient against core; the saga writes to core over HTTP");
        return new WebClientLoanOriginationClient(coreLoanOriginationWebClient);
    }
}

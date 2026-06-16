package com.firefly.lumen.domain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the domain (orchestration) loan-origination service.
 *
 * <p>Skeleton entry point: later chapters add CQRS commands/queries and sagas
 * under {@code com.firefly.lumen.domain}.
 */
@SpringBootApplication
public class DomainLendingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DomainLendingApplication.class, args);
    }
}

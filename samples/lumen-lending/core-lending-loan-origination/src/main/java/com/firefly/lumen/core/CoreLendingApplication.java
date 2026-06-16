package com.firefly.lumen.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the core (system-of-record) loan-origination service.
 *
 * <p>Skeleton entry point: later chapters add entities, repositories, and
 * REST controllers under {@code com.firefly.lumen.core}.
 */
@SpringBootApplication
public class CoreLendingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreLendingApplication.class, args);
    }
}

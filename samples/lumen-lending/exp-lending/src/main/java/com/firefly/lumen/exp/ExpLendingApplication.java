package com.firefly.lumen.exp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the experience (backend-for-frontend) lending service.
 *
 * <p>Skeleton entry point: later chapters add client-facing controllers and
 * aggregation logic under {@code com.firefly.lumen.exp}.
 */
@SpringBootApplication
public class ExpLendingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExpLendingApplication.class, args);
    }
}

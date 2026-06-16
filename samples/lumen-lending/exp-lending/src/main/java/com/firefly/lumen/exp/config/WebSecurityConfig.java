package com.firefly.lumen.exp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Reactive web security for the experience (BFF) tier.
 *
 * <p>Mirrors the real {@code exp-lending} {@code WebSecurityConfig}: it disables the noisy
 * stateless-REST defaults (HTTP Basic, form login, CSRF, logout) that Spring Security auto-activates
 * because {@code fireflyframework-starter-application} brings {@code spring-boot-starter-security}
 * transitively, and permits all HTTP exchanges. Method-level authorization is delegated to the
 * Firefly Framework's {@code SecurityAspect} (AOP) driven by the {@code @Secure} annotation on the
 * controllers, not to this filter chain.
 */
@Configuration
@EnableWebFluxSecurity
public class WebSecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/webjars/**",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()
                        .anyExchange().permitAll()
                )
                .build();
    }
}

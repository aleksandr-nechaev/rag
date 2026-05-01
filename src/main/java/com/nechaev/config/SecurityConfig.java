package com.nechaev.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] ADMIN_PATHS = {
            // Spring Boot Actuator (metrics, health, prometheus)
            "/actuator/**",
            // springdoc OpenAPI / Swagger UI
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            // Springwolf AsyncAPI / WebSocket docs
            "/springwolf/**"
    };

    // Stateless transports that don't carry browser cookies — exempt from CSRF.
    // Scoped to specific API version (and SockJS) so future cookie-based routes don't inherit the exemption.
    // Both this and PerIpRateLimitFilter pull from PublicApiPaths so the two definitions
    // of "what is our public API" cannot drift apart when new versions are added.
    private static final String[] CSRF_EXEMPT_PATHS = PublicApiPaths.antPatternsArray();

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   PerIpRateLimitFilter perIpRateLimitFilter) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ADMIN_PATHS).hasRole("ADMIN")
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .csrf(c -> c.ignoringRequestMatchers(CSRF_EXEMPT_PATHS))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Rate limit before auth so 429 is returned even for unauthenticated abuse on
                // /api/v1/** and /ws/** (which are permitAll); admin paths are skipped via
                // shouldNotFilter, so basic-auth still works normally there.
                .addFilterBefore(perIpRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}

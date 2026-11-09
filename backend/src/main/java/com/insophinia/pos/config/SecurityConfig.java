package com.insophinia.pos.config;

import com.insophinia.pos.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final com.insophinia.pos.security.SystemLockFilter systemLockFilter;
    private final com.insophinia.pos.security.ApiRateLimitFilter apiRateLimitFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, com.insophinia.pos.security.SystemLockFilter systemLockFilter, com.insophinia.pos.security.ApiRateLimitFilter apiRateLimitFilter) {
        this.jwtFilter = jwtFilter;
        this.systemLockFilter = systemLockFilter;
        this.apiRateLimitFilter = apiRateLimitFilter;
    }

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<com.insophinia.pos.security.ApiRateLimitFilter> disableRateLimitContainerRegistration() {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(apiRateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<com.insophinia.pos.security.SystemLockFilter> disableContainerRegistration() {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(systemLockFilter);
        registration.setEnabled(false); // the filter must run only after JWT authentication in Spring Security
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt remains compatible with existing hashes; newly encoded passwords use cost 12.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless JWT API; no cookies
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                    .contentTypeOptions(cto -> {})
                    .frameOptions(fo -> fo.deny())
                    .referrerPolicy(rp -> rp.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                    .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'")))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/2fa/verify").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/customers/verify-email").permitAll() // email-verification callback (token in query)
                    .requestMatchers("/api/system/health", "/actuator/health").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasAuthority("manage:settings")
                    .requestMatchers("/api/cron/**").permitAll() // protected by CRON_SECRET header check in controller
                    .anyRequest().authenticated())
            .exceptionHandling(eh -> eh
                    .authenticationEntryPoint((req, res, ex) -> {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"success\":false,\"message\":\"Authentication required\",\"code\":\"UNAUTHORIZED\"}");
                    })
                    .accessDeniedHandler((req, res, ex) -> {
                        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"success\":false,\"message\":\"Insufficient permissions\",\"code\":\"FORBIDDEN\"}");
                    }))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiRateLimitFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(systemLockFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        String origins = System.getenv().getOrDefault("CORS_ALLOWED_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173");
        config.setAllowedOrigins(java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Request-ID", "X-Display-Channel"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(false); // bearer-token API; no cross-origin cookies are required
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

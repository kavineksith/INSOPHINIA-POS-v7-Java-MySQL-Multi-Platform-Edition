package com.insophinia.pos.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcAuditConfig implements WebMvcConfigurer {
    private final AuditRequestInterceptor audit;
    public WebMvcAuditConfig(AuditRequestInterceptor audit) { this.audit = audit; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(audit); }
}

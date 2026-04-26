package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for Azure Active Directory integration
 * FIXED cr-java-0090: Replaced file-based authentication with Azure AD
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${azure.activedirectory.enabled:false}")
    private boolean azureAdEnabled;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        if (azureAdEnabled) {
            // Azure AD authentication enabled
            http
                .authorizeRequests()
                    .antMatchers("/api/bookings/**").authenticated()
                    .anyRequest().permitAll()
                .and()
                .oauth2Login()
                .and()
                .oauth2ResourceServer()
                    .jwt();
        } else {
            // Development mode - disable security for local testing
            http
                .authorizeRequests()
                    .anyRequest().permitAll()
                .and()
                .csrf().disable();
        }
        
        return http.build();
    }
}

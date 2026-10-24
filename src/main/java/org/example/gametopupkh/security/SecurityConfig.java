package org.example.gametopupkh.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.example.gametopupkh.audit.AdminAuditLogFilter;
import org.example.gametopupkh.audit.service.AuditLogService;
import org.example.gametopupkh.common.response.ApiResponse;
import org.example.gametopupkh.identity.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/**",
            "/api/v1/test",
            "/api/v1/games",
            "/api/v1/games/**",
            "/api/v1/packages/**",
            "/api/v1/dev/payments/**",
            "/api/v1/dev/fulfillments/**",
            "/api/v1/payments/aba/callback",
            "/api/v1/payments/mock/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/error"
    };

    private static final String ADMIN_PATTERN = "/api/v1/admin/**";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
            CustomUserDetailsService userDetailsService, ObjectMapper objectMapper,
            AuditLogService auditLogService, UserRepository userRepository) throws Exception {
        JwtAuthenticationFilter jwtAuthenticationFilter =
                new JwtAuthenticationFilter(jwtService, userDetailsService, objectMapper);
        AdminAuditLogFilter adminAuditLogFilter = new AdminAuditLogFilter(auditLogService, userRepository);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/fulfillments/*/retry")
                                .hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/fulfillments/**")
                                .hasAnyRole("ADMIN", "SUPPORT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/payments/*/refunds")
                                .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/refunds", "/api/v1/admin/refunds/**")
                                .hasAnyRole("ADMIN", "SUPPORT")
                        .requestMatchers(ADMIN_PATTERN).hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(adminAuditLogFilter, JwtAuthenticationFilter.class)
                .build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> writeError(
                objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication is required to access this resource");
    }

    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> writeError(
                objectMapper, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                "The authenticated user is not allowed to access this resource");
    }

    private void writeError(ObjectMapper objectMapper, HttpServletResponse response, int status, String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}

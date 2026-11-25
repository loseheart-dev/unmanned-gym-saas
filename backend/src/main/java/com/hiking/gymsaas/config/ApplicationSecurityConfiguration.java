package com.hiking.gymsaas.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiking.gymsaas.common.exception.ErrorCode;
import com.hiking.gymsaas.common.security.DatabaseJwtAuthenticationConverter;
import com.hiking.gymsaas.common.security.DevAccess;
import com.hiking.gymsaas.common.web.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@Profile("!bootstrap")
public class ApplicationSecurityConfiguration {
    @Bean
    SecurityFilterChain applicationSecurity(HttpSecurity http, ObjectMapper mapper,
                                            DatabaseJwtAuthenticationConverter converter, DevAccess dev) throws Exception {
        return http.csrf(c -> c.disable()).httpBasic(c -> c.disable()).formLogin(c -> c.disable())
                .logout(c -> c.disable()).requestCache(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/admin/auth/login", "/app/auth/wechat-login", "/dev/auth/customer-login").permitAll()
                        .requestMatchers("/dev/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/app/stores", "/app/stores/**").permitAll()
                        .requestMatchers("/admin/**").hasAuthority("ADMIN_IDENTITY")
                        .requestMatchers("/app/**").hasAuthority("CUSTOMER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((req, res, ex) -> write(mapper, res,
                                ex instanceof AuthenticationServiceException ? ErrorCode.DEPENDENCY_UNAVAILABLE : ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((req, res, ex) -> write(mapper, res, ErrorCode.FORBIDDEN)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> write(mapper, res, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((req, res, ex) -> write(mapper, res, ErrorCode.FORBIDDEN)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response,
                            jakarta.servlet.FilterChain chain) throws jakarta.servlet.ServletException, IOException {
                        if (request.getServletPath().startsWith("/dev/") && !dev.enabled()) {
                            write(mapper, response, ErrorCode.NOT_FOUND);
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                }, BearerTokenAuthenticationFilter.class).build();
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), ApiError.of(code));
    }
}

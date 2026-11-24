package com.praveen.multicloud.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praveen.multicloud.service.UserAccountService;
import com.praveen.multicloud.service.ActivityLogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.io.IOException;
import java.util.Map;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            ObjectMapper objectMapper,
            UserAccountService accounts,
            ActivityLogService activities) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/", "/index.html", "/login.html", "/register.html", "/main.html", "/dashboard.html",
                    "/activity.html", "/placement.html", "/forgot-email.html", "/forgot-password.html",
                    "/reset-password.html", "/leakage-analysis.html", "/admin.html",
                    "/js/**", "/css/**", "/favicon.ico").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers("/api/recovery/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(
                    "/api/placement/comparison", "/api/placement/history").hasRole("ADMIN")
                .requestMatchers(
                    "/api/auth/**", "/api/files/**", "/api/dashboard/**",
                    "/api/similarity/**", "/api/placement/**").authenticated()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler((request, response, authentication) -> {
                    AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
                    activities.record(user.getId(), "LOGIN",
                            "Administrator or user session started.");
                    writeJson(response, objectMapper, HttpServletResponse.SC_OK,
                            accounts.getProfile(user.getId()));
                })
                .failureHandler((request, response, exception) ->
                    writeJson(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                            Map.of("error", "Invalid email or password.")))
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> {
                    if (authentication != null
                            && authentication.getPrincipal() instanceof AuthenticatedUser user) {
                        activities.record(user.getId(), "LOGOUT",
                                "Administrator or user session ended.");
                    }
                    writeJson(response, objectMapper, HttpServletResponse.SC_OK,
                            Map.of("message", "Logged out."));
                }))
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.changeSessionId()))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    writeJson(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                            Map.of("error", "Authentication required.")))
                .accessDeniedHandler((request, response, exception) ->
                    writeJson(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                            Map.of("error", "Access denied."))))
            .httpBasic(basic -> basic.disable())
            .requestCache(cache -> cache.disable());
        return http.build();
    }

    private static void writeJson(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            Object body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}

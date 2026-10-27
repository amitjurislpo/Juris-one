package com.jurislpo.jurisone.config;

import com.jurislpo.jurisone.security.JsonAuthEntryPoint;
import com.jurislpo.jurisone.security.OnboardingGateFilter;
import com.jurislpo.jurisone.security.OriginCheckFilter;
import com.jurislpo.jurisone.security.PolicyGateFilter;
import com.jurislpo.jurisone.security.SubscriptionGateFilter;
import com.jurislpo.jurisone.security.JwtAuthFilter;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless: no JSESSIONID. Authentication comes from the {@code jo_at} JWT access-token cookie (JwtAuthFilter), renewed
 * through {@code POST /api/auth/session-refreshes} with the rotating {@code jo_rt} refresh cookie.
 * Spring's CSRF token is off because the app's defence is SameSite=Lax cookies + an Origin check on writes, as today.
 * Permissions are checked in the services with any-of rules.
 * Not loaded for the one-shot CLI commands (migrate / seed / jobs), which run without a web server.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class SecurityConfig {

    /** Reachable without a session. */
    static final String[] PUBLIC = {"/api/auth/session", "/api/auth/session-refreshes", "/api/auth/otp-requests"};

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, JwtAuthFilter sessions, OriginCheckFilter origin, SubscriptionGateFilter subscription,
                               OnboardingGateFilter onboardingGate, PolicyGateFilter policyGate, JsonAuthEntryPoint entryPoint)
            throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .requestCache(r -> r.disable())
            .addFilterAfter(origin, SecurityContextHolderFilter.class)
            .addFilterBefore(sessions, AnonymousAuthenticationFilter.class)
            .addFilterAfter(subscription, JwtAuthFilter.class) // needs the business the JWT filter set
            .addFilterAfter(onboardingGate, SubscriptionGateFilter.class) // 402 before 403: a paused business can't finish anything
            .addFilterAfter(policyGate, OnboardingGateFilter.class) // an unfinished onboarding comes before the policies
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/health").permitAll()
                .requestMatchers(PUBLIC).permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }

    /** The filters run inside the security chain only — not again as plain servlet filters. */
    @Bean
    FilterRegistrationBean<JwtAuthFilter> sessionFilterRegistration(JwtAuthFilter jwtAuthFilter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(jwtAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<SubscriptionGateFilter> subscriptionFilterRegistration(SubscriptionGateFilter subscriptionGateFilter) {
        FilterRegistrationBean<SubscriptionGateFilter> registration = new FilterRegistrationBean<>(subscriptionGateFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<OnboardingGateFilter> onboardingGateFilterRegistration(OnboardingGateFilter onboardingGateFilter) {
        FilterRegistrationBean<OnboardingGateFilter> registration = new FilterRegistrationBean<>(onboardingGateFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<PolicyGateFilter> policyGateFilterRegistration(PolicyGateFilter policyGateFilter) {
        FilterRegistrationBean<PolicyGateFilter> registration = new FilterRegistrationBean<>(policyGateFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<OriginCheckFilter> originFilterRegistration(OriginCheckFilter originCheckFilter) {
        FilterRegistrationBean<OriginCheckFilter> registration = new FilterRegistrationBean<>(originCheckFilter);
        registration.setEnabled(false);
        return registration;
    }

    /** CORS: only the web app's origin, with credentials (the browser normally reaches us through the app's /api proxy). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(JurisOneProperties props) {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOrigins(List.of(props.frontendOrigin()));
        corsConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        corsConfiguration.setAllowedHeaders(List.of("Content-Type", "Accept"));
        corsConfiguration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource corsConfigurationSource = new UrlBasedCorsConfigurationSource();
        corsConfigurationSource.registerCorsConfiguration("/api/**", corsConfiguration);
        return corsConfigurationSource;
    }
}

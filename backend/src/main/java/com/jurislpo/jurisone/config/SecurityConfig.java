package com.jurislpo.jurisone.config;

import static com.jurislpo.jurisone.config.ApiPaths.PREFIX;

import com.jurislpo.jurisone.security.JsonAuthEntryPoint;
import com.jurislpo.jurisone.security.OnboardingGateFilter;
import com.jurislpo.jurisone.security.OriginCheckFilter;
import com.jurislpo.jurisone.security.PolicyGateFilter;
import com.jurislpo.jurisone.security.SubscriptionGateFilter;
import com.jurislpo.jurisone.security.JwtAuthFilter;
import com.jurislpo.jurisone.security.TenantHostFilter;
import com.jurislpo.jurisone.security.TenantOrigins;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

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
    static final String[] PUBLIC = {PREFIX + "/auth/session", PREFIX + "/auth/session-refreshes", PREFIX + "/auth/otp-requests"};

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, JwtAuthFilter sessions, TenantHostFilter tenantHost, OriginCheckFilter origin, SubscriptionGateFilter subscription,
                               OnboardingGateFilter onboardingGate, PolicyGateFilter policyGate, JsonAuthEntryPoint entryPoint,
                               @Value("${jurisone.env:dev}") String environment)
            throws Exception {
        boolean isUpperEnvironment = !"dev".equalsIgnoreCase(environment);
        http
            // The API returns data and files, never pages: nothing may load from or frame its responses. X-Content-Type-Options and
            // X-Frame-Options are Spring's defaults; HSTS (browsers only honour it over https) is sent in every upper environment.
            .headers(h -> {
                h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"));
                h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
                h.httpStrictTransportSecurity(hsts -> {
                    hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true);
                    if (isUpperEnvironment) hsts.requestMatcher(AnyRequestMatcher.INSTANCE);
                });
            })
            .csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .requestCache(r -> r.disable())
            .addFilterAfter(origin, SecurityContextHolderFilter.class)
            .addFilterBefore(tenantHost, OriginCheckFilter.class) // resolves the host first: an unknown host gets the neutral 404 before any Origin or login check
            .addFilterBefore(sessions, AnonymousAuthenticationFilter.class)
            .addFilterAfter(subscription, JwtAuthFilter.class) // needs the business the JWT filter set
            .addFilterAfter(onboardingGate, SubscriptionGateFilter.class) // 402 before 403: a paused business can't finish anything
            .addFilterAfter(policyGate, OnboardingGateFilter.class) // an unfinished onboarding comes before the policies
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/health").permitAll()
                .requestMatchers(PUBLIC).permitAll()
                .requestMatchers(PREFIX + "/**").authenticated()
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
    FilterRegistrationBean<TenantHostFilter> tenantHostFilterRegistration(TenantHostFilter tenantHostFilter) {
        FilterRegistrationBean<TenantHostFilter> registration = new FilterRegistrationBean<>(tenantHostFilter);
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

    /**
     * CORS: the web app's configured origin, with credentials (the browser normally reaches us through the app's /api proxy). With per-business
     * addresses on, the Origin of a business or admin address is allowed too, but only that one exact origin and only when it is this request's own
     * Host ({@link TenantOrigins}); never a wildcard, and any other origin is refused (flag off: it gets the configuration that allows only the web app's origin; flag on: one that allows no origin, so the apex web origin no longer passes).
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(JurisOneProperties props, TenantOrigins tenantOrigins) {
        CorsConfiguration fixed = tenantOrigins.isEnabled() ? new CorsConfiguration() : corsConfiguration(props.frontendOrigin()); // flag on: no apex origin, nothing else matches
        return request -> {
            String origin = request.getHeader("Origin");
            if (origin != null && tenantOrigins.isAllowed(request, origin)) return corsConfiguration(origin);
            return request.getRequestURI().startsWith(PREFIX + "/") ? fixed : null;
        };
    }

    private static CorsConfiguration corsConfiguration(String allowedOrigin) {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOrigins(List.of(allowedOrigin));
        corsConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        corsConfiguration.setAllowedHeaders(List.of("Content-Type", "Accept"));
        corsConfiguration.setAllowCredentials(true);
        return corsConfiguration;
    }
}

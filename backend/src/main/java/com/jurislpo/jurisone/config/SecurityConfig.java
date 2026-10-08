package com.jurislpo.jurisone.config;

import com.jurislpo.jurisone.security.JsonAuthEntryPoint;
import com.jurislpo.jurisone.security.OriginCheckFilter;
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
 * through {@code POST /api/auth/session/refresh} with the rotating {@code jo_rt} refresh cookie.
 * Spring's CSRF token is off because the app's defence is SameSite=Lax cookies + an Origin check on writes, as today.
 * Permissions are checked in the services with the same any-of rules and messages as the Next.js app.
 * Not loaded for the one-shot CLI commands (migrate / seed / jobs), which run without a web server.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class SecurityConfig {

    /** Reachable without a session. */
    static final String[] PUBLIC = {"/api/auth/session", "/api/auth/session/refresh", "/api/auth/password-resets", "/api/auth/password", "/api/auth/tokens/**"};

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtAuthFilter sessions, OriginCheckFilter origin, JsonAuthEntryPoint entryPoint) throws Exception {
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
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/health").permitAll()
                .requestMatchers(PUBLIC).permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }

    /** The filters run inside the security chain only — not again as plain servlet filters. */
    @Bean
    FilterRegistrationBean<JwtAuthFilter> sessionFilterRegistration(JwtAuthFilter f) {
        FilterRegistrationBean<JwtAuthFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }

    @Bean
    FilterRegistrationBean<OriginCheckFilter> originFilterRegistration(OriginCheckFilter f) {
        FilterRegistrationBean<OriginCheckFilter> r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }

    /** CORS: only the Next.js origin, with credentials (the browser normally reaches us through its /api proxy). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(JurisOneProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(props.frontendOrigin()));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        c.setAllowedHeaders(List.of("Content-Type", "Accept"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }
}

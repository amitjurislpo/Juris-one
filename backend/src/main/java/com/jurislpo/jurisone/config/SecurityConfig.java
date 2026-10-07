package com.jurislpo.jurisone.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless: no JSESSIONID. Authentication comes from the existing {@code jo_session} cookie
 * (SessionAuthFilter, added with the auth module). Spring's CSRF token is off because the app's
 * defence is SameSite=Lax cookies + an Origin check on writes, as today.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .requestCache(r -> r.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/health").permitAll()
                .anyRequest().denyAll());
        return http.build();
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

package com.jurislpo.jurisone.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Short-lived access tokens (HS256, {@code JWT_SECRET}). Claims: {@code sub} = employee id, {@code sid} = session id
 * (the refresh-token family), {@code iat}, {@code exp}, {@code jti}. The token proves who the caller is; the request
 * filter still checks the session and the employee on every request, so revocation is immediate.
 */
@Service
public class JwtService {

    public static final String ISSUER = "jurisone";

    /** Access token lifetime: 5 minutes (renewed silently while the user is active). */
    public static final Duration ACCESS_TTL = Duration.ofMinutes(5);

    public record AccessClaims(UUID employeeId, String sessionId) {}

    /** Thrown for a token that was valid but has run out (the caller should refresh). */
    public static class ExpiredException extends RuntimeException {
        ExpiredException() {
            super("expired");
        }
    }

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    public JwtService(@Value("${JWT_SECRET:}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be set to at least 32 bytes (e.g. openssl rand -base64 48)");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // default validators check exp / nbf with a 60 s clock skew; keep the 5-minute promise exact
        this.decoder.setJwtValidator(new org.springframework.security.oauth2.jwt.JwtTimestampValidator(Duration.ZERO));
    }

    public String issue(UUID employeeId, String sessionId) {
        return issue(employeeId, sessionId, Instant.now());
    }

    /** For tests: a token issued at {@code now}. */
    public String issue(UUID employeeId, String sessionId, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(employeeId.toString())
                .claim("sid", sessionId)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TTL))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * The token's claims; {@link ExpiredException} when it has expired, {@link JwtException} when it is malformed,
     * forged or from another issuer.
     */
    public AccessClaims verify(String token) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtValidationException e) {
            if (e.getErrors().stream().anyMatch(err -> err.getDescription() != null && err.getDescription().contains("expired"))) throw new ExpiredException();
            throw e;
        }
        if (!ISSUER.equals(jwt.getClaimAsString("iss"))) throw new JwtException("wrong issuer");
        String sid = jwt.getClaimAsString("sid");
        if (sid == null || jwt.getSubject() == null) throw new JwtException("missing claims");
        return new AccessClaims(UUID.fromString(jwt.getSubject()), sid);
    }
}

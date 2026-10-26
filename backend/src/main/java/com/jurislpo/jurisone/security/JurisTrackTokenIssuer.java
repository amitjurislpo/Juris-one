package com.jurislpo.jurisone.security;

import com.jurislpo.jurisone.config.JurisTrackProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Signs the token JurisTrack verifies ({@code IntegrationTokenVerifier} there): RS256, {@code iss}, {@code aud}, {@code sub} = the
 * JurisOne employee id, {@code email}, {@code name}, {@code businessId}, {@code businessName}, optional {@code managerId},
 * optional {@code shiftCode} (the employee's JurisOne shift, applied by JurisTrack only to a user it creates from that token), {@code trackRole} (MANAGER or EMPLOYEE), {@code iat}, {@code exp} and a unique {@code jti}. One token per forwarded request:
 * JurisTrack accepts a {@code jti} once. A token is never logged or stored.
 */
@Component
public class JurisTrackTokenIssuer {

    /** What the token says about one employee. {@code managerEmployeeId} is null without an assigned manager. */
    public record Subject(UUID employeeId, String email, String fullName, UUID businessId, String businessName, UUID managerEmployeeId, TrackRole trackRole, String shiftCode) {

        public Subject(UUID employeeId, String email, String fullName, UUID businessId, String businessName, UUID managerEmployeeId, TrackRole trackRole) {
            this(employeeId, email, fullName, businessId, businessName, managerEmployeeId, trackRole, null);
        }
    }

    public enum TrackRole { MANAGER, EMPLOYEE }

    private static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(10);
    /** The scope of the service token JurisTrack accepts for {@code PUT /api/integration/employees}. */
    public static final String SERVICE_SCOPE = "employees:sync";

    private final String issuer;
    private final String audience;
    private final Duration lifetime;
    private final RSASSASigner signer;

    public JurisTrackTokenIssuer(JurisTrackProperties properties) {
        this.issuer = properties.issuer();
        this.audience = properties.audience();
        this.lifetime = Duration.ofSeconds(properties.tokenTtlSeconds());
        if (lifetime.compareTo(MAXIMUM_LIFETIME) > 0) throw new IllegalStateException("jurisone.juristrack.token-ttl-seconds must be at most 600");
        if (!properties.enabled()) {
            this.signer = null;
            return;
        }
        if (issuer == null || issuer.isBlank()) throw new IllegalStateException("jurisone.juristrack.issuer must be set when the JurisTrack gateway is enabled");
        this.signer = new RSASSASigner(parsePrivateKey(properties.privateKey()));
    }

    public boolean isEnabled() {
        return signer != null;
    }

    /** A fresh, signed token for one request. */
    public String issue(Subject subject) {
        if (signer == null) throw new IllegalStateException("the JurisTrack gateway is disabled");
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer).audience(audience).subject(subject.employeeId().toString())
                .claim("email", subject.email()).claim("name", subject.fullName())
                .claim("businessId", subject.businessId().toString()).claim("businessName", subject.businessName())
                .claim("trackRole", subject.trackRole().name())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(lifetime))).jwtID(UUID.randomUUID().toString());
        if (subject.managerEmployeeId() != null) claims.claim("managerId", subject.managerEmployeeId().toString());
        if (subject.shiftCode() != null) claims.claim("shiftCode", subject.shiftCode());
        return sign(claims.build());
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            token.sign(signer);
            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the JurisTrack token", e);
        }
    }

    /**
     * A fresh service token for one roster call: no {@code sub}, {@code email} or {@code name}; {@code scope} = {@code employees:sync}, {@code businessId}
     * and {@code businessName} name the business whose roster is sent. One token per call (own {@code jti}), never logged or stored.
     */
    public String issueService(UUID businessId, String businessName) {
        if (signer == null) throw new IllegalStateException("the JurisTrack gateway is disabled");
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).audience(audience).claim("scope", SERVICE_SCOPE)
                .claim("businessId", businessId.toString()).claim("businessName", businessName)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(lifetime))).jwtID(UUID.randomUUID().toString()).build();
        return sign(claims);
    }

    /** A PKCS#8 RSA private key, as a PEM ({@code -----BEGIN PRIVATE KEY-----}) or just the base64 body. */
    static RSAPrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) throw new IllegalStateException("jurisone.juristrack.private-key must be set when the JurisTrack gateway is enabled");
        String base64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (IllegalArgumentException | InvalidKeySpecException | ClassCastException e) {
            throw new IllegalStateException("jurisone.juristrack.private-key is not an RSA private key in PKCS#8 form", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

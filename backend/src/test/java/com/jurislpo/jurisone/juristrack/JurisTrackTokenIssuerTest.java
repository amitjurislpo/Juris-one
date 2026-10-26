package com.jurislpo.jurisone.juristrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.jurisone.JurisTrackStub;
import com.jurislpo.jurisone.config.JurisTrackProperties;
import com.jurislpo.jurisone.security.JurisTrackTokenIssuer;
import com.jurislpo.jurisone.security.JurisTrackTokenIssuer.Subject;
import com.jurislpo.jurisone.security.JurisTrackTokenIssuer.TrackRole;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The token JurisTrack verifies: RS256, the agreed claims, one fresh token (own jti) per call, at most 10 minutes. */
class JurisTrackTokenIssuerTest {

    static final UUID EMPLOYEE = UUID.randomUUID(), BUSINESS = UUID.randomUUID(), MANAGER = UUID.randomUUID();

    static JurisTrackTokenIssuer issuer(boolean enabled, String privateKey, int ttlSeconds) {
        return new JurisTrackTokenIssuer(new JurisTrackProperties(enabled, "http://localhost:8081", "http://localhost:3005", "juristrack", ttlSeconds, privateKey));
    }

    static SignedJWT parse(String token) throws Exception {
        SignedJWT signed = SignedJWT.parse(token);
        assertThat(signed.verify(new RSASSAVerifier(JurisTrackStub.publicKey()))).as("signature").isTrue();
        return signed;
    }

    @Test
    void theTokenIsSignedWithRs256AndCarriesTheEmployee() throws Exception {
        String token = issuer(true, JurisTrackStub.privateKey(), 300).issue(new Subject(EMPLOYEE, "a@b.test", "Asha Rao", BUSINESS, "Nimbus Legal", MANAGER, TrackRole.MANAGER));
        SignedJWT signed = parse(token);
        assertThat(signed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        JWTClaimsSet claims = signed.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:3005");
        assertThat(claims.getAudience()).containsExactly("juristrack");
        assertThat(claims.getSubject()).isEqualTo(EMPLOYEE.toString());
        assertThat(claims.getStringClaim("email")).isEqualTo("a@b.test");
        assertThat(claims.getStringClaim("name")).isEqualTo("Asha Rao");
        assertThat(claims.getStringClaim("businessId")).isEqualTo(BUSINESS.toString());
        assertThat(claims.getStringClaim("businessName")).isEqualTo("Nimbus Legal");
        assertThat(claims.getStringClaim("managerId")).isEqualTo(MANAGER.toString());
        assertThat(claims.getStringClaim("trackRole")).isEqualTo("MANAGER");
    }

    @Test
    void withoutAManagerThereIsNoManagerClaim() throws Exception {
        String token = issuer(true, JurisTrackStub.privateKey(), 300).issue(new Subject(EMPLOYEE, "a@b.test", "Asha Rao", BUSINESS, "Nimbus Legal", null, TrackRole.EMPLOYEE));
        JWTClaimsSet claims = parse(token).getJWTClaimsSet();
        assertThat(claims.getClaims()).doesNotContainKey("managerId");
        assertThat(claims.getStringClaim("trackRole")).isEqualTo("EMPLOYEE");
    }

    @Test
    void everyCallGetsItsOwnTokenThatExpiresWithinTenMinutes() throws Exception {
        JurisTrackTokenIssuer issuer = issuer(true, JurisTrackStub.privateKey(), 300);
        Subject subject = new Subject(EMPLOYEE, "a@b.test", "Asha Rao", BUSINESS, "Nimbus Legal", null, TrackRole.EMPLOYEE);
        JWTClaimsSet first = parse(issuer.issue(subject)).getJWTClaimsSet(), second = parse(issuer.issue(subject)).getJWTClaimsSet();
        assertThat(first.getJWTID()).isNotBlank().isNotEqualTo(second.getJWTID());
        Duration lifetime = Duration.between(first.getIssueTime().toInstant(), first.getExpirationTime().toInstant());
        assertThat(lifetime).isEqualTo(Duration.ofSeconds(300)).isLessThanOrEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void aPemWithHeadersWorksToo() throws Exception {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + JurisTrackStub.privateKey().replaceAll("(.{64})", "$1\n") + "\n-----END PRIVATE KEY-----";
        parse(issuer(true, pem, 300).issue(new Subject(EMPLOYEE, "a@b.test", "Asha Rao", BUSINESS, "Nimbus Legal", null, TrackRole.EMPLOYEE)));
    }

    @Test
    void anEnabledGatewayNeedsAUsableKeyAndALifetimeJurisTrackAccepts() {
        assertThatThrownBy(() -> issuer(true, "", 300)).hasMessageContaining("private-key must be set");
        assertThatThrownBy(() -> issuer(true, "not-a-key", 300)).hasMessageContaining("not an RSA private key");
        assertThatThrownBy(() -> issuer(true, JurisTrackStub.privateKey(), 601)).hasMessageContaining("at most 600");
        assertThat(issuer(false, "", 300).isEnabled()).isFalse();
    }

    @Test
    void theUserTokenCarriesTheShiftCodeOnlyWhenThereIsOne() throws Exception {
        JurisTrackTokenIssuer issuer = issuer(true, JurisTrackStub.privateKey(), 300);
        JWTClaimsSet withShift = parse(issuer.issue(new Subject(EMPLOYEE, "a@b.test", "Asha Rao", BUSINESS, "Nimbus Legal", null, TrackRole.EMPLOYEE, "IN_NIGHT"))).getJWTClaimsSet();
        assertThat(withShift.getStringClaim("shiftCode")).isEqualTo("IN_NIGHT");
        JWTClaimsSet without = parse(issuer.issue(new Subject(EMPLOYEE, "a@b.test", "Asha Rao", BUSINESS, "Nimbus Legal", null, TrackRole.EMPLOYEE))).getJWTClaimsSet();
        assertThat(without.getClaims()).doesNotContainKey("shiftCode");
    }

    @Test
    void theServiceTokenNamesTheBusinessAndNoPerson() throws Exception {
        JurisTrackTokenIssuer issuer = issuer(true, JurisTrackStub.privateKey(), 300);
        SignedJWT signed = parse(issuer.issueService(BUSINESS, "Nimbus Legal"));
        assertThat(signed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        JWTClaimsSet claims = signed.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:3005");
        assertThat(claims.getAudience()).containsExactly("juristrack");
        assertThat(claims.getStringClaim("scope")).isEqualTo("employees:sync");
        assertThat(claims.getStringClaim("businessId")).isEqualTo(BUSINESS.toString());
        assertThat(claims.getStringClaim("businessName")).isEqualTo("Nimbus Legal");
        assertThat(claims.getClaims()).doesNotContainKeys("sub", "email", "name", "trackRole", "managerId", "shiftCode");
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant())).isEqualTo(Duration.ofSeconds(300)).isLessThanOrEqualTo(Duration.ofMinutes(10));
        assertThat(parse(issuer.issueService(BUSINESS, "Nimbus Legal")).getJWTClaimsSet().getJWTID()).isNotBlank().isNotEqualTo(claims.getJWTID());
    }

    @Test
    void aDisabledGatewayIssuesNoServiceToken() {
        assertThatThrownBy(() -> issuer(false, "", 300).issueService(BUSINESS, "Nimbus Legal")).hasMessageContaining("disabled");
    }
}

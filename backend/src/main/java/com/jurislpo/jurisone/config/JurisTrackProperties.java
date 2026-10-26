package com.jurislpo.jurisone.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The JurisOne to JurisTrack gateway ({@code jurisone.juristrack.*}).
 *
 * @param enabled         forward {@code /api/juris-track/**} to JurisTrack; false answers 503 {@code JURISTRACK_DISABLED}
 * @param baseUrl         JurisTrack's address (internal network), without a trailing path: {@code http://localhost:8081}
 * @param issuer          the {@code iss} claim; JurisTrack expects exactly its {@code juristrack.integration.issuer}
 *                        (JurisOne's public URL, {@code jurisone.app-url})
 * @param audience        the {@code aud} claim (default {@code juristrack})
 * @param tokenTtlSeconds how long one token lives (default 300; JurisTrack refuses more than 600)
 * @param privateKey      RSA private key, PKCS#8, PEM or only its base64 body; signs every token (RS256). A secret outside dev.
 */
@ConfigurationProperties(prefix = "jurisone.juristrack")
public record JurisTrackProperties(boolean enabled, String baseUrl, String issuer, String audience, int tokenTtlSeconds, String privateKey) {

    public JurisTrackProperties {
        if (audience == null || audience.isBlank()) audience = "juristrack";
        if (tokenTtlSeconds <= 0) tokenTtlSeconds = 300;
        if (tokenTtlSeconds > 600) throw new IllegalStateException("jurisone.juristrack.token-ttl-seconds must be at most 600 (JurisTrack refuses longer-lived tokens)");
        if (baseUrl != null && baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    /** Never prints the private key (a log line or an exception message that shows the properties must not leak it). */
    @Override
    public String toString() {
        return "JurisTrackProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", issuer=" + issuer + ", audience=" + audience + ", tokenTtlSeconds=" + tokenTtlSeconds + ", privateKey=***]";
    }
}

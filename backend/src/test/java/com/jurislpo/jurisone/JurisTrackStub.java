package com.jurislpo.jurisone;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for JurisTrack in the tests: a local HTTP server on a free port that records every request it gets and answers with
 * whatever {@link #answerWith} says, plus the RSA key pair the gateway signs with (the test context is given the private key,
 * tests verify with the public one). Shared by every test context of the run.
 */
public final class JurisTrackStub {
    private JurisTrackStub() {}

    /** One request as JurisTrack saw it (header names lower-cased). */
    public record Received(String method, String pathAndQuery, Map<String, String> headers, String body) {}

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static volatile int status = 200;
    private static volatile String contentType = "application/json";
    private static volatile String answer = "{}";
    private static volatile boolean hangUp = false;
    /** One-off answers handed out first (status, body) before {@link #answerWith}'s default applies again. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Map.Entry<Integer, String>> QUEUED_ANSWERS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final HttpServer SERVER;
    private static final KeyPair KEYS;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        SERVER.createContext("/", exchange -> {
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String uri = exchange.getRequestURI().getRawPath() + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            RECEIVED.add(new Received(exchange.getRequestMethod(), uri, headers, body));
            if (hangUp) { // JurisTrack gone mid-request: the connection closes without an answer
                exchange.close();
                return;
            }
            Map.Entry<Integer, String> queued = QUEUED_ANSWERS.poll();
            int answerStatus = queued == null ? status : queued.getKey();
            byte[] bytes = (queued == null ? answer : queued.getValue()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Set-Cookie", "juristrack=secret"); // must never reach the browser
            exchange.sendResponseHeaders(answerStatus, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        SERVER.start();
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    /** What JurisTrack answers to a roster push (its summary), for {@link #answerWith}. */
    public static final String ROSTER_SUMMARY = "{\"ok\":true,\"data\":{\"shifts\":{\"created\":1,\"updated\":0,\"unchanged\":0},\"employees\":{\"created\":2,\"updated\":1,\"deactivated\":0,\"reactivated\":0,\"unchanged\":0},\"scheduleEntries\":{\"created\":0,\"updated\":0,\"unchanged\":0,\"skipped\":0},\"skipped\":[]}}";

    /** PKCS#8 private key, base64 (what {@code jurisone.juristrack.private-key} takes). */
    public static String privateKey() {
        return Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded());
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEYS.getPublic();
    }

    public static void answerWith(int statusCode, String type, String body) {
        status = statusCode;
        contentType = type;
        answer = body;
    }

    /** The next request gets this answer (JSON), then the ones queued after it, then the default again. */
    public static void answerNextWith(int statusCode, String body) {
        QUEUED_ANSWERS.add(Map.entry(statusCode, body));
    }

    /** Close every connection without answering (JurisTrack down). */
    public static void hangUp(boolean value) {
        hangUp = value;
    }

    public static List<Received> received() {
        return RECEIVED;
    }

    /** The roster pushes received so far ({@code PUT /api/integration/employees}). */
    public static List<Received> rosterRequests() {
        return RECEIVED.stream().filter(received -> "PUT".equals(received.method()) && "/api/integration/employees".equals(received.pathAndQuery())).toList();
    }

    public static void reset() {
        RECEIVED.clear();
        QUEUED_ANSWERS.clear();
        hangUp = false;
        answerWith(200, "application/json", "{}");
    }
}

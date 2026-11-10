package br.com.datum.customer.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@TestConfiguration(proxyBeanMethods = false)
public class TestJwtSupport {

    public static final String ISSUER = "https://datum.test/realms/datum";
    private static final RSAKey KEY = generateKey();
    private static final RSAKey OTHER_KEY = generateKey();

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("datum-test-key").generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Bean(destroyMethod = "stop")
    JwksFixture jwksFixture() throws IOException {
        return new JwksFixture();
    }

    @Bean
    DynamicPropertyRegistrar jwtProperties(JwksFixture fixture) {
        return registry -> {
            registry.add("customer.security.issuer-uri", () -> ISSUER);
            registry.add("customer.security.jwk-set-uri", fixture::uri);
            registry.add("customer.security.audience", () -> "customer-api");
        };
    }

    public static String token(String role) {
        return token(role, claims -> { });
    }

    public static String token(String role, Consumer<JWTClaimsSet.Builder> customize) {
        var claims = claims(role);
        customize.accept(claims);
        return sign(claims.build(), KEY, JWSAlgorithm.RS256, "datum-test-key");
    }

    private static JWTClaimsSet.Builder claims(String role) {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("test-subject")
                .audience("customer-api").issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)));
        if (role != null) {
            claims.claim("realm_access", Map.of("roles", List.of(role)));
        }
        return claims;
    }

    private static String sign(JWTClaimsSet claims, RSAKey key, JWSAlgorithm algorithm, String keyId) {
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(keyId).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String wrongSignature() {
        return sign(claims("ADMIN").build(), OTHER_KEY, JWSAlgorithm.RS256, "datum-test-key");
    }

    public static String unknownKey() {
        return sign(claims("ADMIN").build(), KEY, JWSAlgorithm.RS256, "unknown-key");
    }

    public static String wrongAlgorithm() {
        return sign(claims("ADMIN").build(), KEY, JWSAlgorithm.RS512, "datum-test-key");
    }

    public static String unsigned() {
        return new PlainJWT(claims("ADMIN").build()).serialize();
    }

    public static void authenticateAsAdmin(TestRestTemplate http) {
        http.getRestTemplate().setInterceptors(List.of((request, body, execution) -> {
            request.getHeaders().setBearerAuth(token("ADMIN"));
            return execution.execute(request, body);
        }));
    }

    static final class JwksFixture {
        private final HttpServer server;

        JwksFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        }

        public void stop() {
            server.stop(0);
        }
    }
}

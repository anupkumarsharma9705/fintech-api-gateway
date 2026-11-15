package com.fintech.fintech_gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Serves a real JWK set over loopback and signs real RS256 tokens. This lets
 * the auth tests exercise Spring Security's decoder (signature, issuer,
 * audience and expiry) without an external authorization server or network
 * dependency.
 */
public final class JwtTestFixture implements AutoCloseable {

    public static final String ISSUER = "https://issuer.example.test";
    public static final String AUDIENCE = "fintech-gateway";
    public static final String KEY_ID = "test-key";

    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey wrongKey;
    private final String jwkSetUri;

    public JwtTestFixture() throws IOException {
        try {
            signingKey = new RSAKeyGenerator(2048)
                    .keyID(KEY_ID)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
            // Same key id as the published key, different key material: the
            // decoder must reject the signature, not merely miss the kid.
            wrongKey = new RSAKeyGenerator(2048)
                    .keyID(KEY_ID)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate test RSA keys", e);
        }

        byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString()
                .getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(jwks);
            }
        });
        server.start();
        jwkSetUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    public String jwkSetUri() {
        return jwkSetUri;
    }

    public String token() {
        return token(signingKey, claims -> { });
    }

    public String token(Consumer<JWTClaimsSet.Builder> customizer) {
        return token(signingKey, customizer);
    }

    public String tokenSignedWithWrongKey() {
        return token(wrongKey, claims -> { });
    }

    private String token(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("client-a")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString())
                .claim("tenant_id", "tenant-a")
                .claim("client_id", "client-a")
                .claim("scope", "gateway.api payments.write");
        customizer.accept(claims);

        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(), claims.build());
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign test token", e);
        }
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

package sn.gouv.mfp.workflow.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Fabrique de jetons de test (émetteur HMAC local uniquement — jamais en production).
 */
public final class JwtTestTokens {

    public static final String ISSUER = "https://test.mfp.local";
    public static final String AUDIENCE = "workflow-api";
    public static final String SECRET = "jeton-test-local-32-octets-min!!";

    private JwtTestTokens() {
    }

    public static String signed(String subject, List<String> roles) {
        return signed(ISSUER, AUDIENCE, subject, roles,
                Instant.now().plusSeconds(300), SECRET);
    }

    public static String signed(
            String issuer,
            String audience,
            String subject,
            List<String> roles,
            Instant expiresAt,
            String secret) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .claim("roles", roles)
                    .expirationTime(Date.from(expiresAt))
                    .issueTime(new Date())
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader(JWSAlgorithm.HS256),
                    claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Signature du jeton de test impossible.", e);
        }
    }
}

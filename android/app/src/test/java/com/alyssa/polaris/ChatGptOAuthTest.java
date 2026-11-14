package com.alyssa.polaris;

import static org.junit.Assert.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Arrays;
import java.util.Date;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

public class ChatGptOAuthTest {
    // Ephemeral test signing material is generated in memory, never written or used for APK signing.
    private static RSAKey key;
    @BeforeClass public static void keys() throws Exception { key = new RSAKeyGenerator(2048).keyID("unit-key").generate(); }
    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ChatGptOAuth.ISSUER).subject("test-subject")
            .audience("issued-test-client").expirationTime(new Date(System.currentTimeMillis() + 60000)).claim("nonce", "test-nonce");
    }
    private String signed(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
    private void reject(JWTClaimsSet.Builder claims) throws Exception {
        String jwt = signed(claims);
        assertThrows(Exception.class, () -> ChatGptOAuth.verifyIdentity(jwt, new JWKSet(key.toPublicJWK()).toString(), "issued-test-client", "test-nonce", System.currentTimeMillis()));
    }
    @Test public void verifiesRealJwtSignatureAndClaims() throws Exception {
        assertEquals("test-subject", ChatGptOAuth.verifyIdentity(signed(claims()), new JWKSet(key.toPublicJWK()).toString(),
            "issued-test-client", "test-nonce", System.currentTimeMillis()).getSubject());
    }
    @Test public void rejectsIssuerAudienceExpiryNonceAndMultiAudienceConfusion() throws Exception {
        reject(claims().issuer("https://other.invalid"));
        reject(claims().audience("different-client"));
        reject(claims().expirationTime(new Date(0)));
        reject(claims().claim("nonce", "different-nonce"));
        reject(claims().audience(Arrays.asList("issued-test-client", "other")));
        reject(claims().claim("azp", "different-client"));
        reject(claims().notBeforeTime(new Date(System.currentTimeMillis() + 120000)));
    }
    @Test public void rejectsUnknownKeyAndTamperedSignature() throws Exception {
        RSAKey other = new RSAKeyGenerator(2048).keyID("other").generate();
        String jwt = signed(claims());
        assertThrows(Exception.class, () -> ChatGptOAuth.verifyIdentity(jwt, new JWKSet(other.toPublicJWK()).toString(), "issued-test-client", "test-nonce", System.currentTimeMillis()));
        String[] parts = jwt.split("\\.");
        String bad = parts[0] + "." + com.nimbusds.jose.util.Base64URL.encode(claims().subject("tampered").build().toString()) + "." + parts[2];
        assertThrows(Exception.class, () -> ChatGptOAuth.verifyIdentity(bad, new JWKSet(key.toPublicJWK()).toString(), "issued-test-client", "test-nonce", System.currentTimeMillis()));
    }
    @Test public void refreshIdentityMayOmitNonceButCannotChangeIt() throws Exception {
        String omitted = signed(claims().claim("nonce", null));
        String jwks = new JWKSet(key.toPublicJWK()).toString();
        assertEquals("test-subject", ChatGptOAuth.verifyIdentity(omitted, jwks, "issued-test-client", "test-nonce", System.currentTimeMillis(), false).getSubject());
        assertThrows(Exception.class, () -> ChatGptOAuth.verifyIdentity(omitted, jwks, "issued-test-client", "test-nonce", System.currentTimeMillis()));
        String wrong = signed(claims().claim("nonce", "wrong"));
        assertThrows(Exception.class, () -> ChatGptOAuth.verifyIdentity(wrong, jwks, "issued-test-client", "test-nonce", System.currentTimeMillis(), false));
    }
    @Test public void pkceUsesS256AndFreshEntropy() throws Exception {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        String first = ChatGptOAuth.randomValue();
        assertEquals(43, first.length()); assertNotEquals(first, ChatGptOAuth.randomValue());
    }
    @Test public void callbackBindsStatePathAndIssuedClient() throws Exception {
        assertEquals("issued", ChatGptOAuth.callback("/auth/callback?code=example&state=state&client_id=issued", "state", ChatGptOAuth.DYNAMIC_CLIENT).get("client_id"));
        assertEquals("issued", ChatGptOAuth.callback("/auth/callback?code=example&state=state", "state", "issued").get("client_id"));
        assertThrows(Exception.class, () -> ChatGptOAuth.callback("/callback?code=example&state=state", "state", "issued"));
        assertThrows(Exception.class, () -> ChatGptOAuth.callback("/auth/callback?code=example&state=wrong", "state", "issued"));
        assertThrows(Exception.class, () -> ChatGptOAuth.callback("/auth/callback?code=example&state=state", "state", ChatGptOAuth.DYNAMIC_CLIENT));
        assertThrows(Exception.class, () -> ChatGptOAuth.callback("/auth/callback?code=example&state=state&client_id=other", "state", "issued"));
        assertThrows(Exception.class, () -> ChatGptOAuth.callback("/auth/callback?code=example&state=state&state=state", "state", "issued"));
    }
    @Test public void deniedAuthorizationStillRequiresValidState() throws Exception {
        assertEquals("access_denied", ChatGptOAuth.callback("/auth/callback?error=access_denied&state=state", "state", "issued").get("error"));
        assertThrows(Exception.class, () -> ChatGptOAuth.callback("/auth/callback?error=access_denied&state=wrong", "state", "issued"));
    }
    @Test public void finalNativeBoundaryRemovesPaidApiFieldsAndHostedTools() throws Exception {
        JSONObject request = new JSONObject().put("model", "account-model").put("input", new JSONArray())
            .put("store", true).put("stream", false).put("temperature", 1).put("max_output_tokens", 500)
            .put("previous_response_id", "previous").put("conversation", "other").put("background", true);
        JSONObject actual = ChatGptOAuth.planBody(request);
        assertFalse(actual.getBoolean("store")); assertTrue(actual.getBoolean("stream"));
        assertEquals(4, actual.length());
        request.put("tools", new JSONArray().put(new JSONObject().put("type", "mcp")));
        assertThrows(Exception.class, () -> ChatGptOAuth.planBody(request));
    }
}

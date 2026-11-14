package com.alyssa.polaris;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure protocol rules, shared by the Android bridge and JVM security tests. */
public final class ChatGptOAuth {
    public static final String ISSUER = "https://auth.openai.com";
    public static final String AUTHORIZE = ISSUER + "/api/accounts/authorize";
    public static final String TOKEN = ISSUER + "/api/accounts/oauth/token";
    public static final String JWKS = ISSUER + "/.well-known/jwks.json";
    public static final String RESOURCE = "https://api.openai.com/v1";
    public static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    public static final String PLAN_SCOPE = "chatgpt.tokens.use.direct";
    public static final String SCOPES = "openid profile email offline_access resource.invoke " + PLAN_SCOPE;
    private ChatGptOAuth() {}

    public static String randomValue() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    public static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static String form(Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return body.toString();
    }

    public static Map<String, String> callback(String target, String state, String requestedClient) throws Exception {
        if (!target.startsWith("/auth/callback?")) throw new Exception("登录回调路径不正确。");
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : target.substring(target.indexOf('?') + 1).split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], "UTF-8");
            if (values.containsKey(key)) throw new Exception("登录回调含重复参数。");
            values.put(key, pair.length == 2 ? URLDecoder.decode(pair[1], "UTF-8") : "");
        }
        if (!same(state, values.get("state"))) throw new Exception("登录回调校验失败。");
        if (values.containsKey("error")) return values;
        String client = values.get("client_id");
        if (DYNAMIC_CLIENT.equals(requestedClient)) {
            if (client == null || client.isEmpty() || DYNAMIC_CLIENT.equals(client)) {
                throw new Exception("注册尚未完成，请重新登录。");
            }
        } else {
            if (client != null && !same(client, requestedClient)) throw new Exception("登录账号注册不匹配。");
            values.put("client_id", requestedClient);
        }
        if (values.getOrDefault("code", "").isEmpty()) throw new Exception("登录未返回授权码。");
        return values;
    }

    public static JWTClaimsSet verifyIdentity(String encoded, String jwks, String client, String nonce, long now) throws Exception {
        return verifyIdentity(encoded, jwks, client, nonce, now, true);
    }

    static JWTClaimsSet verifyIdentity(String encoded, String jwks, String client, String nonce, long now, boolean nonceRequired) throws Exception {
        if (encoded == null || encoded.length() > 65536) throw new Exception("身份凭证格式不正确。");
        SignedJWT jwt = SignedJWT.parse(encoded);
        JWSAlgorithm alg = jwt.getHeader().getAlgorithm();
        String kid = jwt.getHeader().getKeyID();
        boolean valid = false;
        for (JWK key : JWKSet.parse(jwks).getKeys()) {
            if (kid == null || !kid.equals(key.getKeyID())) continue;
            if (key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse())) continue;
            if (key.getAlgorithm() != null && !alg.equals(key.getAlgorithm())) continue;
            if (JWSAlgorithm.RS256.equals(alg) && key instanceof RSAKey) {
                valid = jwt.verify(new RSASSAVerifier(((RSAKey) key).toPublicJWK()));
            } else if (JWSAlgorithm.ES256.equals(alg) && key instanceof ECKey) {
                valid = jwt.verify(new ECDSAVerifier(((ECKey) key).toPublicJWK()));
            }
            if (valid) break;
        }
        if (!valid) throw new Exception("身份签名校验失败，请重新登录。");
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        if (!ISSUER.equals(claims.getIssuer()) || !claims.getAudience().contains(client)
            || claims.getExpirationTime() == null || claims.getExpirationTime().getTime() <= now
            || claims.getSubject() == null || claims.getSubject().isEmpty()
            || ((nonceRequired || claims.getClaim("nonce") != null) && !same(nonce, claims.getStringClaim("nonce")))
            || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().getTime() > now + 30000)
            || (claims.getIssueTime() != null && claims.getIssueTime().getTime() > now + 60000)
            || (claims.getAudience().size() > 1 && !client.equals(claims.getStringClaim("azp")))
            || (claims.getClaim("azp") != null && !client.equals(claims.getStringClaim("azp")))) {
            throw new Exception("身份信息校验失败，请重新登录。");
        }
        return claims;
    }

    public static boolean hasPlanScope(String scope) {
        for (String part : scope.trim().split("\\s+")) if (PLAN_SCOPE.equals(part)) return true;
        return false;
    }

    /** A second boundary: the native transport never forwards caller-supplied extra parameters. */
    public static JSONObject planBody(JSONObject input) throws Exception {
        if (input.optString("model").trim().isEmpty() || !(input.opt("input") instanceof JSONArray)) {
            throw new Exception("请选择订阅账号可用的模型，并提供完整上下文。");
        }
        JSONObject body = new JSONObject();
        for (String field : new String[] {"model", "input", "instructions", "tools", "tool_choice", "reasoning"}) {
            if (input.has(field)) body.put(field, input.get(field));
        }
        body.put("store", false);
        body.put("stream", true);
        JSONArray tools = body.optJSONArray("tools");
        if (tools != null) for (int i = 0; i < tools.length(); i++) {
            JSONObject ns = tools.getJSONObject(i);
            if (!"namespace".equals(ns.optString("type")) || !"polaris".equals(ns.optString("name"))) {
                throw new Exception("订阅线路仅支持已验证的本地函数工具。");
            }
            JSONArray functions = ns.getJSONArray("tools");
            for (int j = 0; j < functions.length(); j++) {
                if (!"function".equals(functions.getJSONObject(j).optString("type"))) {
                    throw new Exception("订阅线路不支持该托管工具。");
                }
            }
        }
        return body;
    }
}

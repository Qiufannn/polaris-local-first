package com.alyssa.polaris;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

public class GardenOAuthTest {
    @Test public void pinsResourceAndOAuthEndpointsAndSeparatesMemoryProfiles() throws Exception {
        assertEquals(MemorySourcePolicy.GARDEN_MCP,MemorySourcePolicy.resource(MemorySourcePolicy.GARDEN,MemorySourcePolicy.GARDEN_MCP));
        for(String url:new String[]{MemorySourcePolicy.GARDEN_MCP+"?token=unit","https://galatea.abysslumina.com.evil.invalid/mcp",MemorySourcePolicy.NOTION_MCP,"http://galatea.abysslumina.com/mcp"}) assertThrows(Exception.class,()->MemorySourcePolicy.resource(MemorySourcePolicy.GARDEN,url));
        assertNotEquals(MemorySourcePolicy.key("unit",MemorySourcePolicy.GARDEN,MemorySourcePolicy.GARDEN_MCP,""),MemorySourcePolicy.key("unit",MemorySourcePolicy.NOTION,MemorySourcePolicy.NOTION_MCP,""));
        assertEquals("https://galatea.abysslumina.com/oauth/token",MemorySourcePolicy.oauthEndpoint(MemorySourcePolicy.GARDEN,"token"));
    }
    @Test public void publicClientUsesPublishedCodeGrantResourceAndPKCEWithoutSecret() throws Exception {
        MemorySourceTest.Vault vault=new MemorySourceTest.Vault();AtomicInteger requests=new AtomicInteger();
        MemorySourceSession session=new MemorySourceSession(MemorySourcePolicy.GARDEN,MemorySourcePolicy.GARDEN_MCP,"",vault,(u,m,b,t,a,h)->{
            requests.incrementAndGet();assertNull(a);assertFalse(b.contains("client_secret"));
            if(u.endsWith("/register")){JSONObject input=new JSONObject(b);assertEquals("none",input.getString("token_endpoint_auth_method"));assertEquals(1,input.getJSONArray("grant_types").length());assertEquals("authorization_code",input.getJSONArray("grant_types").getString(0));return new MemorySourceHttp.Result(201,new JSONObject().put("client_id","unit-garden-client").put("token_endpoint_auth_method","none").put("redirect_uris",input.getJSONArray("redirect_uris")).toString());}
            assertTrue(u.endsWith("/oauth/token"));assertTrue(b.contains("code_verifier=unit-verifier"));assertTrue(b.contains("resource=https%3A%2F%2Fgalatea.abysslumina.com%2Fmcp"));
            return new MemorySourceHttp.Result(200,new JSONObject().put("access_token","unit-private-garden-access").put("token_type","Bearer").put("resource",MemorySourcePolicy.GARDEN_MCP).put("expires_in",604800).toString());
        });
        String redirect="http://127.0.0.1:51234/auth/callback",client=session.registerNotion(redirect);session.notionExchange("unit-code",client,redirect,"unit-verifier",session.generation());
        assertEquals(2,requests.get());assertTrue(session.status().getBoolean("connected"));assertFalse(session.status().toString().contains("unit-private"));assertFalse(session.status().getBoolean("writingEnabled"));
    }
    @Test public void cancelledGrantCannotReplaceCredentialsAndWrongResourceIsRejected() throws Exception {
        for(boolean cancel:new boolean[]{true,false}) {
            MemorySourceTest.Vault vault=new MemorySourceTest.Vault();final MemorySourceSession[] holder=new MemorySourceSession[1];
            holder[0]=new MemorySourceSession(MemorySourcePolicy.GARDEN,MemorySourcePolicy.GARDEN_MCP,"",vault,(u,m,b,t,a,h)->{if(cancel)holder[0].cancel();return new MemorySourceHttp.Result(200,new JSONObject().put("token_type","bearer").put("access_token","unit-private-access").put("resource",cancel?MemorySourcePolicy.GARDEN_MCP:MemorySourcePolicy.NOTION_RESOURCE).toString());});
            long generation=holder[0].generation();assertThrows(Exception.class,()->holder[0].notionExchange("code","client","http://127.0.0.1:51234/auth/callback","verifier",generation));assertFalse(holder[0].status().getBoolean("connected"));
        }
    }
    @Test public void optionalIssuerStillRejectsWrongIssuerAndProviderErrors() throws Exception {
        Map<String,String> callback=new HashMap<>();MemorySourcePolicy.browserIssuer(MemorySourcePolicy.GARDEN,callback);
        callback.put("iss",MemorySourcePolicy.GARDEN_ORIGIN);MemorySourcePolicy.browserIssuer(MemorySourcePolicy.GARDEN,callback);
        callback.put("iss",MemorySourcePolicy.NOTION_RESOURCE);assertThrows(Exception.class,()->MemorySourcePolicy.browserIssuer(MemorySourcePolicy.GARDEN,callback));
        callback.clear();callback.put("error","access_denied");assertThrows(Exception.class,()->MemorySourcePolicy.browserIssuer(MemorySourcePolicy.GARDEN,callback));
        assertThrows(Exception.class,()->MemorySourcePolicy.browserIssuer(MemorySourcePolicy.NOTION,new HashMap<>()));
    }
    @Test public void bearerCannotGoToWebsiteOrOtherProvidersAndDisconnectOnlyClearsLocalVault() throws Exception {
        MemorySourceTest.Vault vault=new MemorySourceTest.Vault();vault.data.put("connection",new JSONObject().put("kind",MemorySourcePolicy.GARDEN).put("resource",MemorySourcePolicy.GARDEN_MCP).put("access_token","unit-private-access").put("expires_at",0));AtomicInteger calls=new AtomicInteger();
        MemorySourceSession session=new MemorySourceSession(MemorySourcePolicy.GARDEN,MemorySourcePolicy.GARDEN_MCP,"",vault,(u,m,b,t,a,h)->{calls.incrementAndGet();assertEquals(MemorySourcePolicy.GARDEN_MCP,u);assertEquals("unit-private-access",a);return new MemorySourceHttp.Result(200,"{}");});
        for(String url:new String[]{MemorySourcePolicy.GARDEN_ORIGIN+"/api/machines/mine",MemorySourcePolicy.NOTION_MCP,MemorySourcePolicy.GARDEN_MCP+"?token=unit"})assertThrows(Exception.class,()->session.sourceRequest(url,"POST","{}",new HashMap<>()));
        assertEquals(0,calls.get());session.authorized(MemorySourcePolicy.GARDEN_MCP,"POST","{}",new HashMap<>());assertEquals(1,calls.get());session.disconnect();assertFalse(session.status().getBoolean("connected"));assertEquals(1,calls.get());
    }
    @Test public void normalGardenRpcIsAvailableWithoutOpeningMemoryWriters() throws Exception {
        JSONObject rpc=new JSONObject().put("jsonrpc","2.0").put("method","tools/call").put("params",new JSONObject().put("name","get_self").put("arguments",new JSONObject()));
        assertEquals("get_self",MemorySourcePolicy.rpc(MemorySourcePolicy.GARDEN,rpc.toString()).getJSONObject("params").getString("name"));
        rpc.getJSONObject("params").put("name","https://elsewhere.invalid/secret");assertThrows(Exception.class,()->MemorySourcePolicy.rpc(MemorySourcePolicy.GARDEN,rpc.toString()));
        MemorySourceTest.Vault vault=new MemorySourceTest.Vault();MemorySourceSession session=new MemorySourceSession(MemorySourcePolicy.GARDEN,MemorySourcePolicy.GARDEN_MCP,"",vault,(u,m,b,t,a,h)->{throw new AssertionError();});
        assertThrows(Exception.class,()->session.setWritingEnabled(true));assertThrows(Exception.class,()->session.setDiaryWriting(true,"11111111111111111111111111111111","22222222222222222222222222222222"));
    }
}

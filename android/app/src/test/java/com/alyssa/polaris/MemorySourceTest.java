package com.alyssa.polaris;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
public class MemorySourceTest {
 private static final String REPO="https://api.github.com/repos/unit-owner/unit-memory", CLIENT="Iv1.0123456789abcdef";
 static final class Vault implements ChatGptSession.Storage {
  JSONObject data=new JSONObject();
  public JSONObject load() throws Exception{return new JSONObject(data.toString());}
  public void save(JSONObject v)throws Exception{data=new JSONObject(v.toString());}
  public String hostId(){return "unit-host";} public void reset(){data=new JSONObject();}
 }
 private JSONObject token(String access,String refresh)throws Exception{
  JSONObject r=new JSONObject().put("token_type","bearer").put("access_token",access).put("expires_in",3600).put("scope","");
  if(refresh!=null)r.put("refresh_token",refresh);return r;
 }
 private void seed(Vault v,String kind,String resource,long expires)throws Exception{
  v.data.put("connection",new JSONObject().put("kind",kind).put("resource",resource).put("client_id",CLIENT).put("access_token","ghu_unit_old").put("refresh_token","ghr_unit_old").put("expires_at",expires));
 }
 @Test public void validatesOriginsPublicIdsFilesAndPageReferences()throws Exception{
  assertEquals(REPO,MemorySourcePolicy.resource(MemorySourcePolicy.GITHUB,REPO));
  for(String bad:new String[]{REPO+"?token=unit",REPO+"/extra","https://api.github.com/repos/../repo","https://api.github.com.evil.invalid/repos/a/b","http://api.github.com/repos/a/b"})assertThrows(Exception.class,()->MemorySourcePolicy.resource(MemorySourcePolicy.GITHUB,bad));
  MemorySourcePolicy.publicClient(CLIENT);
  for(String secret:new String[]{"ghp_unit_secret","ghu_unit_secret","12345","Ov23unitOauthApp"})assertThrows(Exception.class,()->MemorySourcePolicy.publicClient(secret));
  assertEquals("continuity/identity.md",MemorySourcePolicy.file("continuity/identity.md"));
  for(String path:new String[]{"../private.md","continuity/../private.md","continuity/%2e%2e/private.md","continuity/keys.json","other/identity.md"})assertThrows(Exception.class,()->MemorySourcePolicy.file(path));
  String id="0123456789abcdef0123456789abcdef";
  assertEquals(id,MemorySourcePolicy.page("https://app.notion.com/p/"+id+"?pvs=204"));
  assertThrows(Exception.class,()->MemorySourcePolicy.page("https://evil.invalid/"+id));
  assertThrows(Exception.class,()->MemorySourcePolicy.page("http://notion.so/"+id));
 }
 @Test public void separatesProfilesAndRejectsWritesAndWrongIssuer()throws Exception{
  assertNotEquals(MemorySourcePolicy.key("mcp-unit",MemorySourcePolicy.GITHUB,REPO,CLIENT),MemorySourcePolicy.key("mcp-unit",MemorySourcePolicy.NOTION,MemorySourcePolicy.NOTION_MCP,""));
  for(String name:new String[]{"notion-update-page","notion-create-pages","github_memory_update"})assertThrows(Exception.class,()->MemorySourcePolicy.rpc(MemorySourcePolicy.NOTION,"{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\""+name+"\"}}"));
  Map<String,String> v=new HashMap<>();v.put("iss",MemorySourcePolicy.NOTION_RESOURCE);MemorySourcePolicy.notionIssuer(v);v.put("iss","https://other.invalid");assertThrows(Exception.class,()->MemorySourcePolicy.notionIssuer(v));
 }
 @Test public void respectsDevicePollIntervalsWithoutExposingPrivateDeviceCode()throws Exception{
  Vault vault=new Vault();AtomicLong now=new AtomicLong(1000);AtomicInteger polls=new AtomicInteger();
  MemorySourceSession s=new MemorySourceSession(MemorySourcePolicy.GITHUB,REPO,CLIENT,vault,(u,m,b,t,a,h)->{
   assertNull(a);assertFalse(b.contains("client_secret"));
   if(u.endsWith("/device/code")){assertTrue(b.contains("offline_access"));return new MemorySourceHttp.Result(200,"{\"device_code\":\"unit-private-device\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://github.com/login/device\",\"interval\":5,\"expires_in\":900}");}
   if(polls.incrementAndGet()==1)return new MemorySourceHttp.Result(200,"{\"error\":\"slow_down\",\"interval\":10}");
   return new MemorySourceHttp.Result(200,token("ghu_unit_new","ghr_unit_new").toString());
  },now::get);
  assertFalse(s.githubStart().toString().contains("unit-private-device"));assertEquals("pending",s.githubPoll().getString("state"));assertEquals(0,polls.get());
  now.addAndGet(5000);assertEquals(10,s.githubPoll().getInt("pollAfterSeconds"));now.addAndGet(5000);s.githubPoll();assertEquals(1,polls.get());
  now.addAndGet(5000);assertEquals("connected",s.githubPoll().getString("state"));assertTrue(s.status().getBoolean("connected"));assertFalse(s.status().toString().contains("ghu_"));assertFalse(s.status().toString().contains("ghr_"));
 }
 @Test public void publicNotionRegistrationAndFreshGrantDoNotMixRefreshTokens()throws Exception{
  Vault vault=new Vault();seed(vault,MemorySourcePolicy.NOTION,MemorySourcePolicy.NOTION_RESOURCE,0);
  MemorySourceSession s=new MemorySourceSession(MemorySourcePolicy.NOTION,MemorySourcePolicy.NOTION_MCP,"",vault,(u,m,b,t,a,h)->{
   assertNull(a);assertFalse(b.contains("client_secret"));
   if(u.endsWith("/register")){JSONObject r=new JSONObject(b);assertEquals("none",r.getString("token_endpoint_auth_method"));return new MemorySourceHttp.Result(201,new JSONObject().put("client_id","unit-notion-client").put("token_endpoint_auth_method","none").put("redirect_uris",r.getJSONArray("redirect_uris")).toString());}
   assertTrue(b.contains("resource=https%3A%2F%2Fmcp.notion.com"));assertTrue(b.contains("code_verifier=unit-verifier"));return new MemorySourceHttp.Result(200,token("unit-notion-access",null).toString());
  });
  String redirect="http://127.0.0.1:51234/auth/callback",client=s.registerNotion(redirect);s.notionExchange("unit-code",client,redirect,"unit-verifier",s.generation());
  assertFalse(vault.data.toString().contains("ghr_unit_old"));assertTrue(s.status().getBoolean("connected"));assertEquals(51234,s.notionPort());
 }
 @Test public void cancelBeforeNetworkCompletionPreventsCredentialCommit()throws Exception{
  Vault vault=new Vault();final MemorySourceSession[] holder=new MemorySourceSession[1];
  holder[0]=new MemorySourceSession(MemorySourcePolicy.NOTION,MemorySourcePolicy.NOTION_MCP,"",vault,(u,m,b,t,a,h)->{holder[0].cancel();return new MemorySourceHttp.Result(200,token("unit-access","unit-refresh").toString());});
  long before=holder[0].generation();assertThrows(Exception.class,()->holder[0].notionExchange("code","client","http://127.0.0.1:51234/auth/callback","verifier",before));assertFalse(holder[0].status().getBoolean("connected"));
 }
 @Test public void resourceBoundRefreshAndRevocationNeverSendCredentialsElsewhere()throws Exception{
  Vault vault=new Vault();seed(vault,MemorySourcePolicy.GITHUB,REPO,1);AtomicInteger calls=new AtomicInteger();
  MemorySourceSession s=new MemorySourceSession(MemorySourcePolicy.GITHUB,REPO,CLIENT,vault,(u,m,b,t,a,h)->{calls.incrementAndGet();assertTrue(u.startsWith("https://github.com/login/oauth/"));assertFalse(b.contains("client_secret"));return new MemorySourceHttp.Result(400,"{\"error\":\"bad_refresh_token\"}");});
  assertThrows(Exception.class,()->s.authorized(MemorySourcePolicy.NOTION_MCP,"POST","{}",new HashMap<>()));assertEquals(0,calls.get());
  assertThrows(Exception.class,()->s.authorized(REPO+"/contents/README.md","GET",null,new HashMap<>()));assertFalse(s.status().getBoolean("connected"));assertFalse(vault.data.toString().contains("ghr_unit_old"));
 }
 @Test public void cancelledGithubApprovalCannotRestoreTheConnection()throws Exception{
  Vault vault=new Vault();AtomicLong now=new AtomicLong(1000);final MemorySourceSession[] holder=new MemorySourceSession[1];
  holder[0]=new MemorySourceSession(MemorySourcePolicy.GITHUB,REPO,CLIENT,vault,(u,m,b,t,a,h)->{
   if(u.endsWith("/device/code"))return new MemorySourceHttp.Result(200,"{\"device_code\":\"unit-device\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900}");
   holder[0].cancel();return new MemorySourceHttp.Result(200,token("ghu_unit_cancelled","ghr_unit_cancelled").toString());
  },now::get);
  holder[0].githubStart();now.addAndGet(5000);assertThrows(Exception.class,()->holder[0].githubPoll());assertFalse(holder[0].status().getBoolean("connected"));assertFalse(vault.data.toString().contains("ghu_"));
 }
 @Test public void githubReadChecksBlobContentAndDoesNotFollowDownloadUrls()throws Exception{
  Vault vault=new Vault();seed(vault,MemorySourcePolicy.GITHUB,REPO,0);byte[] bytes="# unit memory\n".getBytes(StandardCharsets.UTF_8);
  MessageDigest hash=MessageDigest.getInstance("SHA-1");hash.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.US_ASCII));hash.update(bytes);StringBuilder sha=new StringBuilder();for(byte b:hash.digest())sha.append(String.format("%02x",b&255));
  MemorySourceSession s=new MemorySourceSession(MemorySourcePolicy.GITHUB,REPO,CLIENT,vault,(u,m,b,t,a,h)->{
   assertEquals(REPO+"/contents/continuity/identity.md",u);assertEquals("GET",m);assertEquals("ghu_unit_old",a);
   return new MemorySourceHttp.Result(200,new JSONObject().put("type","file").put("path","continuity/identity.md").put("encoding","base64").put("size",bytes.length).put("sha",sha.toString()).put("content",Base64.getEncoder().encodeToString(bytes)).put("download_url","https://untrusted.invalid/file").toString());
  });assertEquals("# unit memory\n",s.githubFile("continuity/identity.md").getString("text"));
 }
 @Test public void readsMatchingSseRpcWithoutWaitingForEof()throws Exception{
  byte[] bytes=("data: {\"jsonrpc\":\"2.0\",\"id\":\"other\",\"result\":{}}\n\n"+"data: {\"jsonrpc\":\"2.0\",\"id\":\"wanted\",\"result\":{\"text\":\"你好\"}}\n\n").getBytes(StandardCharsets.UTF_8);
  ByteArrayInputStream input=new ByteArrayInputStream(bytes){public synchronized int read(byte[] b,int o,int l){if(available()==0)throw new AssertionError("Must not wait for EOF");return super.read(b,o,l);}};
  assertTrue(MemorySourceHttp.sse(input,"wanted").contains("你好"));assertThrows(Exception.class,()->MemorySourceHttp.sse(new ByteArrayInputStream("data: {}\n\n".getBytes(StandardCharsets.UTF_8)),"wanted"));
 }
}

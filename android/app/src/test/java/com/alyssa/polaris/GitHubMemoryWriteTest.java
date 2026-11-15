package com.alyssa.polaris;

import static org.junit.Assert.*;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public class GitHubMemoryWriteTest {
    static final String REPO="https://api.github.com/repos/unit-owner/unit-memory", CLIENT="Iv1.0123456789abcdef", PATH="continuity/unit-memory.md", COMMIT="cccccccccccccccccccccccccccccccccccccccc";
    static class Vault implements ChatGptSession.Storage {
        JSONObject data=new JSONObject(); boolean failReceipt;
        public JSONObject load() throws Exception { return new JSONObject(data.toString()); }
        public void save(JSONObject value) throws Exception { if(failReceipt&&value.has("github_write_receipts"))throw new java.io.IOException("unit-private-disk-error"); data=new JSONObject(value.toString()); }
        public String hostId(){return "unit-host";} public void reset(){data=new JSONObject();}
    }
    static class Repo implements MemorySourceHttp.Transport {
        String text; int puts, failureStatus; boolean loseResponse, badReadback, badReceipt; String readbackUrl; Map<String,String> snapshot=new HashMap<>();
        Repo(String text){this.text=text;}
        JSONObject file(String path,String value)throws Exception{
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
            return new JSONObject().put("type","file").put("path",path).put("encoding","base64").put("size",bytes.length).put("sha",GitHubMemoryWriter.blob(bytes,40)).put("content",Base64.getEncoder().encodeToString(bytes));
        }
        public MemorySourceHttp.Result send(String url,String method,String body,String type,String bearer,Map<String,String> headers)throws Exception{
            assertEquals("ghu_unit_test",bearer); assertTrue(url.startsWith(REPO+"/contents/"));
            URI uri=new URI(url); String path=uri.getPath().substring(new URI(REPO).getPath().length()+"/contents/".length());
            if("GET".equals(method)){
                String value=text;
                if(uri.getRawQuery()!=null){assertEquals("ref="+COMMIT,uri.getRawQuery());readbackUrl=url;value=badReadback?"other valid contents":snapshot.get(COMMIT);}
                return value==null?new MemorySourceHttp.Result(404,"{}"):new MemorySourceHttp.Result(200,file(path,value).toString());
            }
            assertEquals("PUT",method);assertNull(uri.getRawQuery());puts++;
            if(failureStatus>0&&failureStatus<500)return new MemorySourceHttp.Result(failureStatus,"{\"message\":\"unit-private-provider-error\"}");
            JSONObject input=new JSONObject(body);
            if(text!=null)assertEquals(GitHubMemoryWriter.blob(text.getBytes(StandardCharsets.UTF_8),40),input.getString("sha"));else assertFalse(input.has("sha"));
            text=new String(Base64.getDecoder().decode(input.getString("content")),StandardCharsets.UTF_8);snapshot.put(COMMIT,text);
            if(loseResponse){loseResponse=false;throw new SocketTimeoutException("unit-private-response");}
            if(failureStatus>=500)return new MemorySourceHttp.Result(failureStatus,"unit-private-server-body");
            JSONObject meta=new JSONObject().put("path",badReceipt?"other.md":path).put("sha",GitHubMemoryWriter.blob(text.getBytes(StandardCharsets.UTF_8),40));
            return new MemorySourceHttp.Result(201,new JSONObject().put("content",meta).put("commit",new JSONObject().put("sha",COMMIT)).toString());
        }
    }
    Vault vault()throws Exception{
        Vault v=new Vault();v.data.put("connection",new JSONObject().put("kind",MemorySourcePolicy.GITHUB).put("resource",REPO).put("client_id",CLIENT).put("access_token","ghu_unit_test").put("expires_at",0));return v;
    }
    MemorySourceSession session(Vault v,Repo r)throws Exception{return new MemorySourceSession(MemorySourcePolicy.GITHUB,REPO,CLIENT,v,r);}
    JSONObject args(String operation,String expected,String content)throws Exception{return new JSONObject().put("path",PATH).put("operation",operation).put("expected_sha",expected).put("content",content);}
    String sha(String text)throws Exception{return GitHubMemoryWriter.blob(text.getBytes(StandardCharsets.UTF_8),40);}

    @Test public void requiresNativeOptInAndActualUserTurnAuthorizationBeforeAnyRequest()throws Exception{
        Vault v=vault();Repo r=new Repo(null);MemorySourceSession s=session(v,r);GitHubMemoryWriter w=new GitHubMemoryWriter(s);JSONObject a=args("create","new","unit memory");
        assertThrows(Exception.class,()->w.write(a,true));assertEquals(0,r.puts);s.setWritingEnabled(true);
        a.put("userWriteRequested",true);assertThrows(Exception.class,()->w.write(a,false));assertEquals(0,r.puts);
        s.setWritingEnabled(false);assertThrows(Exception.class,()->w.write(a,true));assertEquals(0,r.puts);
    }
    @Test public void createsThenReadsTheExactCommitSnapshot()throws Exception{
        Vault v=vault();Repo r=new Repo(null);MemorySourceSession s=session(v,r);s.setWritingEnabled(true);
        JSONObject receipt=new GitHubMemoryWriter(s).write(args("create","new","# Unit memory\n"),true);
        assertTrue(receipt.getBoolean("verified"));assertTrue(receipt.getBoolean("created_commit"));assertEquals(COMMIT,receipt.getString("commit_sha"));assertEquals(REPO+"/contents/"+PATH+"?ref="+COMMIT,r.readbackUrl);assertEquals(1,r.puts);
        assertTrue(receipt.getString("commit_url").startsWith("https://github.com/unit-owner/unit-memory/commit/"));
    }
    @Test public void appendKeepsExistingFactsAndReopenDoesNotDuplicateTheCommit()throws Exception{
        Vault v=vault();Repo r=new Repo("existing facts");MemorySourceSession s=session(v,r);s.setWritingEnabled(true);
        JSONObject a=args("append",sha(r.text),"new fact\n"),first=new GitHubMemoryWriter(s).write(a,true);
        assertEquals("existing facts\nnew fact\n",r.text);assertTrue(first.getBoolean("verified"));
        JSONObject second=new GitHubMemoryWriter(session(v,r)).write(a,true);
        assertEquals("already_current",second.getString("status"));assertEquals(COMMIT,second.getString("commit_sha"));assertFalse(second.getBoolean("created_commit"));assertEquals(1,r.puts);
        assertFalse(v.data.getJSONArray("github_write_receipts").toString().contains("new fact"));
    }
    @Test public void correctionChangesOnlyTheUniqueLiteralSnippet()throws Exception{
        Vault v=vault();Repo r=new Repo("before\nold preference\nafter\n");MemorySourceSession s=session(v,r);s.setWritingEnabled(true);
        JSONObject receipt=new GitHubMemoryWriter(s).write(args("replace_text",sha(r.text),"correct preference").put("old_text","old preference"),true);
        assertTrue(receipt.getBoolean("verified"));assertEquals("before\ncorrect preference\nafter\n",r.text);
    }
    @Test public void aVerifiedCreateIsNotReplayedAfterAnotherWriterDeletesTheFile()throws Exception{
        Vault v=vault();Repo r=new Repo(null);MemorySourceSession s=session(v,r);s.setWritingEnabled(true);JSONObject a=args("create","new","fact");
        new GitHubMemoryWriter(s).write(a,true);r.text=null;
        assertEquals("conflict",new GitHubMemoryWriter(s).write(a,true).getString("status"));assertEquals(1,r.puts);assertNull(r.text);
    }
    @Test public void staleShaAndCreateOverExistingFileNeverSendPut()throws Exception{
        Vault v=vault();Repo r=new Repo("facts");MemorySourceSession s=session(v,r);s.setWritingEnabled(true);GitHubMemoryWriter w=new GitHubMemoryWriter(s);
        assertEquals("conflict",w.write(args("append",sha("stale"),"new"),true).getString("status"));assertEquals("conflict",w.write(args("create","new","new"),true).getString("status"));assertEquals(0,r.puts);assertEquals("facts",r.text);
    }
    @Test public void ambiguousOrMissingCorrectionIsRejectedWithoutChangingAnything()throws Exception{
        Vault v=vault();Repo r=new Repo("fact fact");MemorySourceSession s=session(v,r);s.setWritingEnabled(true);GitHubMemoryWriter w=new GitHubMemoryWriter(s);
        for(String old:new String[]{"fact","missing"})assertEquals("patch_mismatch",w.write(args("replace_text",sha(r.text),"new").put("old_text",old),true).getString("status"));assertEquals(0,r.puts);
    }
    @Test public void lostPutResponseReconcilesWithoutClaimingAnUnknownCommitOrDuplicatingAppend()throws Exception{
        Vault v=vault();Repo r=new Repo("facts\n");r.loseResponse=true;MemorySourceSession s=session(v,r);s.setWritingEnabled(true);JSONObject a=args("append",sha(r.text),"new fact\n");
        JSONObject receipt=new GitHubMemoryWriter(s).write(a,true);assertTrue(receipt.getBoolean("verified"));assertTrue(receipt.isNull("commit_sha"));assertTrue(receipt.isNull("created_commit"));assertEquals("verified_without_commit_receipt",receipt.getString("status"));new GitHubMemoryWriter(s).write(a,true);assertEquals(1,r.puts);
    }
    @Test public void serverFailureAfterProcessingIsAlsoReconciledWithoutRetryingPut()throws Exception{
        Vault v=vault();Repo r=new Repo(null);r.failureStatus=500;MemorySourceSession s=session(v,r);s.setWritingEnabled(true);
        JSONObject receipt=new GitHubMemoryWriter(s).write(args("create","new","fact"),true);assertEquals("verified_without_commit_receipt",receipt.getString("status"));assertEquals(1,r.puts);assertTrue(receipt.isNull("commit_sha"));
    }
    @Test public void unconfirmedNetworkFailureIsNotClaimedAsASuccessfulSave()throws Exception{
        Vault v=vault();Repo r=new Repo(null){
            public MemorySourceHttp.Result send(String url,String method,String body,String type,String bearer,Map<String,String> headers)throws Exception{
                if("PUT".equals(method)){puts++;throw new SocketTimeoutException("unit-private-request");}
                return super.send(url,method,body,type,bearer,headers);
            }
        };
        MemorySourceSession s=session(v,r);s.setWritingEnabled(true);
        JSONObject receipt=new GitHubMemoryWriter(s).write(args("create","new","fact"),true);
        assertEquals("outcome_unknown",receipt.getString("status"));assertFalse(receipt.getBoolean("verified"));assertTrue(receipt.isNull("created_commit"));assertTrue(receipt.isNull("commit_sha"));assertEquals(1,r.puts);
    }
    @Test public void acceptedCommitWithMismatchedReadbackIsNotReportedAsVerified()throws Exception{
        Vault v=vault();Repo r=new Repo(null);r.badReadback=true;MemorySourceSession s=session(v,r);s.setWritingEnabled(true);
        JSONObject receipt=new GitHubMemoryWriter(s).write(args("create","new","fact"),true);assertEquals("submitted_unverified",receipt.getString("status"));assertFalse(receipt.getBoolean("verified"));assertEquals(COMMIT,receipt.getString("commit_sha"));assertTrue(receipt.getBoolean("created_commit"));
    }
    @Test public void malformedCommitMetadataDoesNotBecomeAFalseCommitReceipt()throws Exception{
        Vault v=vault();Repo r=new Repo(null);r.badReceipt=true;MemorySourceSession s=session(v,r);s.setWritingEnabled(true);
        JSONObject receipt=new GitHubMemoryWriter(s).write(args("create","new","fact"),true);assertEquals("verified_without_commit_receipt",receipt.getString("status"));assertTrue(receipt.isNull("commit_sha"));
    }
    @Test public void forbiddenWritesExplainTheExistingAppPermissionUpdateWithoutProviderBodyLeaks()throws Exception{
        Vault v=vault();Repo r=new Repo(null);r.failureStatus=403;MemorySourceSession s=session(v,r);s.setWritingEnabled(true);
        JSONObject receipt=new GitHubMemoryWriter(s).write(args("create","new","fact"),true);assertEquals("permission_required",receipt.getString("status"));assertFalse(receipt.getBoolean("verified"));assertTrue(receipt.getString("message").contains("Read and write"));assertFalse(receipt.toString().contains("unit-private"));assertNull(r.text);
    }
    @Test public void diskFailureBeforeSubmissionPreventsTheNetworkWrite()throws Exception{
        Vault v=vault();Repo r=new Repo(null);MemorySourceSession s=session(v,r);s.setWritingEnabled(true);v.failReceipt=true;
        Exception failure=assertThrows(Exception.class,()->new GitHubMemoryWriter(s).write(args("create","new","fact"),true));assertTrue(MemorySourceOAuth.safe(failure).contains("尚未发出"));assertEquals(0,r.puts);
    }
    @Test public void validatesWriteOperationsAndKeepsWritesInsideThisMemoryResource()throws Exception{
        for(JSONObject bad:new JSONObject[]{args("delete","new","fact"),args("create","new",""),args("append","new","fact"),args("replace_text",sha("facts"),"new"),args("create","new","fact").put("path",".github/workflows/test.md")})assertThrows(Exception.class,()->MemorySourcePolicy.validateWrite(bad));
        Vault v=vault();Repo r=new Repo("facts");MemorySourceSession s=session(v,r);s.setWritingEnabled(true);
        for(String bad:new String[]{REPO+"/contents/continuity/../private.md",REPO+"/contents/README.md?token=private",REPO+"/contents/README.md?ref=main","https://api.github.com/repos/other/repo/contents/README.md"})assertThrows(Exception.class,()->s.sourceRequest(bad,"PUT","{}",new HashMap<>()));assertEquals(0,r.puts);
        assertEquals(1,GitHubMemoryTools.catalog(false).getJSONArray("tools").length());assertEquals(2,GitHubMemoryTools.catalog(true).getJSONArray("tools").length());
    }
}

package com.alyssa.polaris;

import static org.junit.Assert.*;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class NotionDiaryWriteTest {
    static final String PARENT="11111111111111111111111111111111", TEMPLATE="22222222222222222222222222222222", PAGE="33333333333333333333333333333333", OTHER="44444444444444444444444444444444", DATE="2026-11-15";
    static class Vault implements ChatGptSession.Storage {
        JSONObject data=new JSONObject(); boolean fail;
        public JSONObject load() throws Exception { return new JSONObject(data.toString()); }
        public void save(JSONObject value) throws Exception { if(fail)throw new java.io.IOException("unit vault failure");data=new JSONObject(value.toString()); }
        public String hostId(){return "unit-notion-host";} public void reset(){data=new JSONObject();}
    }
    static JSONObject tool(String name, JSONObject properties) throws Exception { return new JSONObject().put("name",name).put("inputSchema", new JSONObject().put("type","object").put("properties",properties)); }
    static String page(String id,String title,String body,String parent) throws Exception {
        return "<page url=\"https://www.notion.so/"+id+"\"><ancestor-path>"+(parent.isEmpty()?"":"<parent-page url=\"https://www.notion.so/"+parent+"\" title=\"unit\"/>")+"</ancestor-path><properties>"+new JSONObject().put("title",title)+"</properties><content>\n"+body+"\n</content></page>";
    }
    static class Notion implements MemorySourceHttp.Transport {
        String body, parent=PARENT, title=DATE+"｜Unit diary"; int creates,updates; boolean loseResponse, failBeforeMutation, badReadback, duplicate, emptyAncestors, noAppend, failReadback; boolean failReceipt;
        String oldBody;
        JSONObject updateTool() throws Exception {
            return tool("notion-update-page",new JSONObject().put("page_id",new JSONObject()).put("command",new JSONObject().put("enum",new JSONArray().put(noAppend?"replace_content":"insert_content"))).put("content",new JSONObject()).put("position",new JSONObject()));
        }
        public MemorySourceHttp.Result send(String url,String method,String text,String type,String bearer,Map<String,String> headers) throws Exception {
            assertEquals(MemorySourcePolicy.NOTION_MCP,url); assertEquals("POST",method); assertEquals("unit-notion-access",bearer);
            JSONObject request=new JSONObject(text), params=request.getJSONObject("params"), result=new JSONObject();
            if("tools/list".equals(request.getString("method"))) result.put("tools",new JSONArray().put(tool("notion-fetch",new JSONObject().put("id",new JSONObject()))).put(tool("notion-create-pages",new JSONObject().put("parent",new JSONObject()).put("pages",new JSONObject()))).put(updateTool()));
            else {
                String name=params.getString("name"); JSONObject args=params.getJSONObject("arguments");
                if("notion-fetch".equals(name)) {
                    String id=args.getString("id"), output;
                    if(MemorySourcePolicy.NOTION_MARKDOWN.equals(id))output="# Notion-flavored Markdown\n";
                    else if(TEMPLATE.equals(id))output=page(id,"Unit template","## 我们共同经历的这一天\n记忆锚点\n## 阿昼自己的日记","");
                    else if(PARENT.equals(id)) output=page(id,"Unit Polaris directory",body==null?"No diary yet":"<page url=\"https://www.notion.so/"+PAGE+"\">"+title+"</page>"+(duplicate?"<page url=\"https://www.notion.so/"+OTHER+"\">"+title+"</page>":""),"");
                    else {
                        assertEquals(PAGE,id); if(failReadback&&creates+updates>0)throw new SocketTimeoutException("unit readback");
                        output=page(id,title,badReadback?"wrong body":body,emptyAncestors?"":parent);
                    }
                    result.put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",output)));
                } else {
                    if(failBeforeMutation){creates++;throw new SocketTimeoutException("unit unknown write");}
                    if("notion-create-pages".equals(name)){creates++;assertEquals(PARENT,args.getJSONObject("parent").getString("page_id"));JSONArray pages=args.getJSONArray("pages");assertEquals(1,pages.length());JSONObject input=pages.getJSONObject(0);title=input.getJSONObject("properties").getString("title");body=input.getString("content");}
                    else {assertEquals("notion-update-page",name);updates++;assertEquals(PAGE,args.getString("page_id"));assertEquals("insert_content",args.getString("command"));assertEquals("end",args.getJSONObject("position").getString("type"));assertFalse(args.has("allow_deleting_content"));body+=args.getString("content");}
                    if(loseResponse){loseResponse=false;throw new SocketTimeoutException("unit lost result");}
                    result.put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","unit accepted")));
                }
            }
            return new MemorySourceHttp.Result(200,new JSONObject().put("jsonrpc","2.0").put("id",request.get("id")).put("result",result).toString());
        }
    }
    Vault vault() throws Exception { Vault v=new Vault();v.data.put("connection",new JSONObject().put("kind",MemorySourcePolicy.NOTION).put("resource",MemorySourcePolicy.NOTION_RESOURCE).put("access_token","unit-notion-access").put("expires_at",0));return v; }
    MemorySourceSession session(Vault v,Notion n) throws Exception { return new MemorySourceSession(MemorySourcePolicy.NOTION,MemorySourcePolicy.NOTION_MCP,"",v,n); }
    JSONObject args(String shared) throws Exception { return new JSONObject().put("date",DATE).put("title","Unit diary").put("shared_memory",shared).put("memory_anchors",new JSONArray().put("unit fact one").put("unit fact two").put("unit fact three")).put("private_diary","I remember the actual unit scene."); }
    JSONObject write(MemorySourceSession s,JSONObject a) throws Exception { return new NotionDiaryWriter(s,new HashMap<>()).write(a,true); }
    @Test public void requiresNativePermissionAndActualUserFlag() throws Exception {
        Notion n=new Notion();MemorySourceSession s=session(vault(),n);assertThrows(Exception.class,()->write(s,args("facts")));s.setDiaryWriting(true,PARENT,TEMPLATE);
        assertThrows(Exception.class,()->new NotionDiaryWriter(s,new HashMap<>()).write(args("facts"),false));s.setDiaryWriting(false,"","");assertThrows(Exception.class,()->write(s,args("facts")));assertEquals(0,n.creates+n.updates);
    }
    @Test public void createsInConfiguredDirectoryAndReadsBackBothLayers() throws Exception {
        Notion n=new Notion();Vault v=vault();MemorySourceSession s=session(v,n);s.setDiaryWriting(true,PARENT,TEMPLATE);JSONObject r=write(s,args("An actual shared scene."));
        assertTrue(r.getBoolean("verified"));assertEquals(1,n.creates);assertEquals(0,n.updates);assertTrue(n.body.contains("我们共同经历的这一天"));assertTrue(n.body.contains("阿昼自己的日记"));assertFalse(v.data.getJSONArray("notion_diary_receipts").toString().contains("An actual shared scene."));
    }
    @Test public void appendsSameDateWithoutChangingEarlierPrivateText() throws Exception {
        Notion n=new Notion();n.body="## old shared\nOriginal fact\n## 阿昼自己的日记\nMy private original.";String original=n.body;MemorySourceSession s=session(vault(),n);s.setDiaryWriting(true,PARENT,TEMPLATE);
        assertTrue(write(s,args("Later shared scene.")).getBoolean("verified"));assertTrue(n.body.startsWith(original));assertEquals(0,n.creates);assertEquals(1,n.updates);
    }
    @Test public void parentChildListingProvesScopeWhenProviderOmitsAncestors() throws Exception {
        Notion n=new Notion();n.emptyAncestors=true;MemorySourceSession s=session(vault(),n);s.setDiaryWriting(true,PARENT,TEMPLATE);assertTrue(write(s,args("Actual scene.")).getBoolean("verified"));
    }
    @Test public void refusesWrongParentAndDuplicateDatesBeforeMutation() throws Exception {
        for(boolean duplicate:new boolean[]{false,true}) {Notion n=new Notion();n.body="old private";n.parent=OTHER;n.duplicate=duplicate;MemorySourceSession s=session(vault(),n);s.setDiaryWriting(true,PARENT,TEMPLATE);assertThrows(Exception.class,()->write(s,args("scene")));assertEquals(0,n.creates+n.updates);}
    }
    @Test public void repeatRequestAcrossRestartOnlyVerifiesExistingWrite() throws Exception {
        Notion n=new Notion();Vault v=vault();MemorySourceSession s=session(v,n);s.setDiaryWriting(true,PARENT,TEMPLATE);assertTrue(write(s,args("same scene")).getBoolean("verified"));
        assertTrue(write(session(v,n),args("same scene")).getBoolean("verified"));assertEquals(1,n.creates+n.updates);
    }
    @Test public void lostResponseIsNotBlindlyRetriedAndCanBeReconciled() throws Exception {
        Notion n=new Notion();n.loseResponse=true;Vault v=vault();MemorySourceSession s=session(v,n);s.setDiaryWriting(true,PARENT,TEMPLATE);
        assertFalse(write(s,args("same scene")).getBoolean("verified"));assertTrue(write(session(v,n),args("same scene")).getBoolean("verified"));assertEquals(1,n.creates+n.updates);
    }
    @Test public void unknownSubmissionWithoutPageBlocksDuplicateRetry() throws Exception {
        Notion n=new Notion();n.failBeforeMutation=true;MemorySourceSession s=session(vault(),n);s.setDiaryWriting(true,PARENT,TEMPLATE);
        assertFalse(write(s,args("scene")).getBoolean("verified"));assertFalse(write(s,args("scene")).getBoolean("verified"));assertEquals(1,n.creates);assertNull(n.body);
        assertThrows(Exception.class,()->write(s,args("reworded scene")));assertEquals(1,n.creates);
    }
    @Test public void diskFailureBeforeSubmissionPreventsMutation() throws Exception {
        Notion n=new Notion();Vault v=vault();MemorySourceSession s=session(v,n);s.setDiaryWriting(true,PARENT,TEMPLATE);v.fail=true;
        assertThrows(Exception.class,()->write(s,args("scene")));assertEquals(0,n.creates+n.updates);
    }
    @Test public void failedReadbackNeverBecomesVerifiedOrAnotherWrite() throws Exception {
        Notion n=new Notion();n.failReadback=true;MemorySourceSession s=session(vault(),n);s.setDiaryWriting(true,PARENT,TEMPLATE);assertFalse(write(s,args("scene")).getBoolean("verified"));
        assertThrows(Exception.class,()->write(s,args("scene")));assertEquals(1,n.creates);n.failReadback=false;assertTrue(write(s,args("scene")).getBoolean("verified"));
    }
    @Test public void incorrectReadbackOrLaterDeletionDoesNotRetainVerifiedFlag() throws Exception {
        Notion n=new Notion();MemorySourceSession s=session(vault(),n);s.setDiaryWriting(true,PARENT,TEMPLATE);assertTrue(write(s,args("scene")).getBoolean("verified"));n.body="changed externally";
        assertFalse(write(s,args("scene")).getBoolean("verified"));assertEquals(1,n.creates+n.updates);
    }
    @Test public void unsupportedProviderNeverFallsBackToReplaceContent() throws Exception {
        Notion n=new Notion();n.body="old private";n.noAppend=true;MemorySourceSession s=session(vault(),n);s.setDiaryWriting(true,PARENT,TEMPLATE);assertThrows(Exception.class,()->write(s,args("scene")));assertEquals(0,n.creates+n.updates);
    }
    @Test public void validatesDateBothLayersAndMarkupBeforeMutation() throws Exception {
        Notion n=new Notion();MemorySourceSession s=session(vault(),n);s.setDiaryWriting(true,PARENT,TEMPLATE);
        for(JSONObject a:new JSONObject[]{args("scene").put("date","2026-02-30"),args("scene").put("private_diary",""),args("scene").put("memory_anchors",new JSONArray().put("one")),args("<page url=\"unit\">nested</page>")})assertThrows(Exception.class,()->write(s,a));assertEquals(0,n.creates+n.updates);
    }
    @Test public void keepsRawWorkspaceMutationsBlockedAndOnlyWhitelistsMarkdownResource() throws Exception {
        for(String name:new String[]{"notion-create-pages","notion-update-page","notion-delete-page"})assertThrows(Exception.class,()->MemorySourcePolicy.rpc(MemorySourcePolicy.NOTION,new JSONObject().put("jsonrpc","2.0").put("method","tools/call").put("params",new JSONObject().put("name",name)).toString()));
        JSONObject fetch=new JSONObject().put("jsonrpc","2.0").put("method","tools/call").put("params",new JSONObject().put("name","notion-fetch").put("arguments",new JSONObject().put("id",MemorySourcePolicy.NOTION_MARKDOWN)));
        MemorySourcePolicy.rpc(MemorySourcePolicy.NOTION,fetch.toString());fetch.getJSONObject("params").getJSONObject("arguments").put("id","notion://docs/other");assertThrows(Exception.class,()->MemorySourcePolicy.rpc(MemorySourcePolicy.NOTION,fetch.toString()));
    }
    @Test public void wrapsLegacyAppendSchemaAndRejectsAmbiguousSelection() throws Exception {
        JSONObject fields=new JSONObject().put("command",new JSONObject().put("enum",new JSONArray().put("insert_content_after"))).put("new_str",new JSONObject()).put("selection_with_ellipsis",new JSONObject());
        JSONObject tool=tool("notion-update-page",new JSONObject().put("data",new JSONObject().put("properties",fields)));
        JSONObject r=NotionDiaryWriter.appendArgs(tool,new NotionDiaryWriter.Page(PAGE,"unit","old\nunique end",PARENT),"new fragment");assertEquals("unique end",r.getJSONObject("data").getString("selection_with_ellipsis"));
        assertThrows(Exception.class,()->NotionDiaryWriter.appendArgs(tool,new NotionDiaryWriter.Page(PAGE,"unit","same\nsame",PARENT),"new"));
    }
    @Test public void markdownLinksCannotAuthorizeAnUnrelatedPage() throws Exception {
        NotionDiaryWriter.Page p=new NotionDiaryWriter.Page(PARENT,"dir","["+DATE+"｜linked elsewhere](https://www.notion.so/"+OTHER+")","");assertTrue(NotionDiaryWriter.dates(p,DATE).isEmpty());
    }
}

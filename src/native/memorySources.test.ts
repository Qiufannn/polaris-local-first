import { afterEach, describe, expect, it, vi } from 'vitest';
const mocks=vi.hoisted(()=>({native:true,available:true,request:vi.fn(),generic:vi.fn()}));
vi.mock('@capacitor/core',()=>({Capacitor:{isNativePlatform:()=>mocks.native,getPlatform:()=>mocks.native?'android':'web',isPluginAvailable:()=>mocks.available},CapacitorHttp:{request:mocks.generic},registerPlugin:()=>({request:mocks.request})}));
import { clearMcpToolCatalogCacheForTests, invokeMcpTool, resolveMcpToolCatalog } from '../engines/mcpRuntime';
import { normalizeMcpServer, parseMcpServersJson, serializeMcpServersToJson } from '../stores/runtimeStoreMcp';
import { buildServerHeaders } from '../engines/mcpRuntimeTransport';
import type { McpServerConfig } from '../types/domain';
function source(kind:'notion-memory-oauth'|'github-memory-device'):McpServerConfig{return{id:'mcp-unit',handle:'memory',name:'Memory',description:'',transport:'streamable-http',authKind:kind,url:kind==='notion-memory-oauth'?'https://mcp.notion.com/mcp':'https://api.github.com/repos/unit-owner/unit-memory',publicClientId:kind==='github-memory-device'?'Iv1.0123456789abcdef':undefined,headers:[],isActive:true};}
afterEach(()=>{vi.clearAllMocks();vi.unstubAllGlobals();mocks.native=true;mocks.available=true;clearMcpToolCatalogCacheForTests();});
describe('native primary memory sources',()=>{
 it.each(['notion-memory-oauth','github-memory-device'] as const)('retains resource and public profile through catalog and invocation: %s',async kind=>{
  const server=source(kind),fetch=vi.fn();vi.stubGlobal('fetch',fetch);
  const name=kind==='notion-memory-oauth'?'notion-fetch':'github_memory_read';
  mocks.request.mockImplementation(async({body})=>{const r=JSON.parse(body);if(r.method==='notifications/initialized')return{status:202,data:'',headers:{}};
   const result=r.method==='initialize'?{protocolVersion:'2025-03-26',capabilities:{tools:{}}}:r.method==='tools/list'?{tools:[{name,inputSchema:{type:'object'}}]}:{content:[{type:'text',text:'synthetic memory'}]};
   return{status:200,data:JSON.stringify({jsonrpc:'2.0',id:r.id,result}),headers:{'mcp-session-id':'unit-session'}};});
  const catalog=await resolveMcpToolCatalog({servers:[server],retryDelaysMs:[],timeoutSeconds:1});expect(catalog.errors).toEqual([]);expect(catalog.tools).toHaveLength(1);
  const result=await invokeMcpTool({tool:catalog.tools[0],argumentsObject:kind==='notion-memory-oauth'?{id:'0123456789abcdef0123456789abcdef'}:{path:'continuity/identity.md'},timeoutSeconds:1});expect(result.ok).toBe(true);
  for(const [args]of mocks.request.mock.calls){expect(args.authKind).toBe(kind);expect(args.url).toBe(server.url);expect(args.publicClientId).toBe(server.publicClientId);expect(args.headers.Authorization).toBeUndefined();}
  expect(mocks.request.mock.calls.some(([a])=>a.headers['Mcp-Session-Id']==='unit-session')).toBe(true);expect(fetch).not.toHaveBeenCalled();expect(mocks.generic).not.toHaveBeenCalled();
 });
 it.each(['web','missing-bridge','legacy-sse'])('never falls back when unavailable: %s',async mode=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);mocks.native=mode!=='web';mocks.available=mode!=='missing-bridge';
  const s=source('notion-memory-oauth');if(mode==='legacy-sse')s.transport='sse';
  const result=await resolveMcpToolCatalog({servers:[s],retryDelaysMs:[],timeoutSeconds:1});expect(result.errors).toHaveLength(1);expect(result.tools).toEqual([]);expect(fetch).not.toHaveBeenCalled();expect(mocks.generic).not.toHaveBeenCalled();expect(mocks.request).not.toHaveBeenCalled();
 });
 it('exports only public connection metadata and strips credential headers or a misplaced secret',()=>{
  const s={...source('github-memory-device'),headers:[{id:'unit',key:'Authorization',value:'private-unit-sentinel'}]};
  expect(normalizeMcpServer(s).headers).toEqual([]);expect(buildServerHeaders(s).has('Authorization')).toBe(false);
  const text=serializeMcpServersToJson([s]);expect(text).not.toContain('private-unit-sentinel');expect(parseMcpServersJson(text)[0]).toMatchObject({authKind:'github-memory-device',publicClientId:'Iv1.0123456789abcdef',headers:[]});
  expect(serializeMcpServersToJson([{...s,publicClientId:'ghp_private-unit-sentinel'}])).not.toContain('private-unit-sentinel');
 });
});

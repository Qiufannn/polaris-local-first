import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it, vi } from 'vitest';
const script=readFileSync('android/app/src/main/assets/original-home-download.js','utf8');
const origin='https://unit.chatgpt.site',url='blob:'+origin+'/unit-blob';
async function run({page=origin,blobUrl=url,clickUrl=url,age=0,mime='text/plain',size=4}={}) {
 const fetch=vi.fn().mockResolvedValue({ok:true,blob:async()=>({type:mime,size})});
 class Reader { result='data:text/plain;base64,dGVzdA==';onload?:()=>void;readAsDataURL(){this.onload?.();} }
 const global:Record<string,unknown>={location:{origin:page},URL,fetch,FileReader:Reader,__polarisDownloadClick:{url:clickUrl,name:'Unit book.txt',at:Date.now()-age}};
 runInNewContext(script,global);await(global.__polarisHomeDownload as (key:string,url:string,origin:string)=>Promise<void>)('reply',blobUrl,origin);
 return {fetch,result:global.reply as {error?:string;ready:boolean;data?:string;mime?:string}};
}
describe('user-clicked original home exports',()=>{
 it('reads only the clicked same-origin blob, with no credentials or remote native interface',async()=>{
  const {fetch,result}=await run();expect(result.ready).toBe(true);expect(result.data).toBe('dGVzdA==');expect(fetch).toHaveBeenCalledExactlyOnceWith(url);
  expect(script).not.toMatch(/document\.cookie|Authorization|addJavascriptInterface|CookieManager/);
 });
 it.each([{page:'https://elsewhere.invalid'},{blobUrl:'blob:https://elsewhere.invalid/unit'},{blobUrl:'https://unit.chatgpt.site/book'},{clickUrl:'blob:'+origin+'/other'},{age:40000}])('rejects a changed origin, stale click or unclicked blob before reading (%j)',async options=>{const {fetch,result}=await run(options);expect(fetch).not.toHaveBeenCalled();expect(result.error).toBeTruthy();expect(result.data).toBeUndefined();});
 it('bounds exported files and rejects unsupported MIME types without producing a file',async()=>{expect((await run({size:17*1024*1024})).result.error).toContain('16 MB');expect((await run({mime:'application/octet-stream'})).result.error).toContain('TXT');});
});

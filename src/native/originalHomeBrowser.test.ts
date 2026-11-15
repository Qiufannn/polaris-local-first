import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it, vi } from 'vitest';
const source = readFileSync('android/app/src/main/assets/original-home-request.js', 'utf8');
async function invoke(response: Partial<Response> | Error, body = '{"jsonrpc":"2.0","id":1,"method":"tools/list"}', page = 'https://unit.chatgpt.site/api/home/bridge') {
  const fetch = response instanceof Error ? vi.fn().mockRejectedValue(response) : vi.fn().mockResolvedValue(response);
  const global: Record<string, unknown> = { fetch, location: new URL(page), document: { baseURI: 'https://elsewhere.invalid/' } };
  runInNewContext(source, global);
  await (global.__polarisHomeRequest as (key: string, body: string, origin: string) => Promise<void>)('reply', body, 'https://unit.chatgpt.site');
  return { fetch, result: global.reply as { done: boolean; status?: number; data?: string; error?: string } };
}
function response(status = 200, type = 'application/json', text = '{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}') { return { status, ok: status < 300, headers: new Headers({ 'Content-Type': type }), text: async () => text }; }
describe('original home authenticated browser requests', () => {
  it('uses the normal same-origin browser session without reading or copying credentials', async () => {
    const body = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'todo_save', arguments: { title: 'Synthetic </script> "; throw new Error()', client_request_id: 'unit-stable-id' } } });
    const { fetch, result } = await invoke(response(), body);
    expect(result.done).toBe(true); expect(result.status).toBe(200);
    expect(fetch).toHaveBeenCalledExactlyOnceWith('https://unit.chatgpt.site/api/home/rpc', expect.objectContaining({ method: 'POST', body, credentials: 'same-origin', redirect: 'error', cache: 'no-store' }));
    expect(Object.keys(fetch.mock.calls[0][1].headers)).toEqual(['Content-Type', 'Accept', 'X-Home-Action']);
    expect(source).not.toMatch(/document\.cookie|CookieManager|Authorization|authenticated-user|User-Agent|addJavascriptInterface/);
  });
  it('stops before sending data if the page origin or bridge path changes, and ignores document base URLs', async () => {
    for (const page of ['https://elsewhere.invalid/api/home/bridge', 'https://unit.chatgpt.site/']) {
      const { fetch, result } = await invoke(response(), '{}', page); expect(fetch).not.toHaveBeenCalled(); expect(result.error).toContain('地址不一致');
    }
  });
  it.each([401, 403, 404, 500])('reports HTTP %i safely without retrying or passing response secrets to chat', async status => {
    const { fetch, result } = await invoke(response(status, 'text/html', 'private-authentication-html'));
    expect(result.error).toBeTruthy(); expect(result.error).not.toContain('private-authentication-html'); expect(result.data).toBeUndefined(); expect(fetch).toHaveBeenCalledTimes(1);
    if (status === 403) expect(result.error).toContain('不必反复登录');
  });
  it('accepts empty notification acknowledgements, and rejects login HTML and invalid RPC payloads', async () => {
    expect((await invoke(response(202, ''))).result).toEqual({ done: true, status: 202, data: '' });
    expect((await invoke(response(200, 'text/html', 'private-login-html'))).result.error).toContain('非 JSON');
    expect((await invoke(response(200, 'application/json', '{"ok":true}'))).result.error).toContain('协议');
  });
  it('bounds response sizes and treats redirect/network failures as unknown outcomes without retry', async () => {
    const oversized = response(); oversized.headers.set('Content-Length', '9999999');
    expect((await invoke(oversized)).result.error).toContain('过大');
    const { fetch, result } = await invoke(new Error('temporary secret from browser'));
    expect(fetch).toHaveBeenCalledTimes(1); expect(result.error).toContain('先读回'); expect(result.error).not.toContain('temporary secret');
  });
});

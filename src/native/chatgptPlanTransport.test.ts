import { afterEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({
  available: true, native: true, listener: null as null | ((event: any) => void),
  remove: vi.fn(async () => undefined), cancel: vi.fn(async () => undefined), start: vi.fn(), genericStart: vi.fn()
}));
vi.mock('@capacitor/core', () => ({
  Capacitor: { isNativePlatform: () => mocks.native, getPlatform: () => 'android', isPluginAvailable: () => mocks.available },
  registerPlugin: (name: string) => ({
    start: name === 'ChatGptPlanHttp' ? mocks.start : mocks.genericStart, cancel: mocks.cancel,
    addListener: async (_name: string, listener: (event: any) => void) => { mocks.listener = listener; return { remove: mocks.remove }; }
  })
}));
import { executeBuiltRequest } from '../engines/chat-api/chatApiTransport';
import { requestAssistantReply } from '../engines/chatApi';
import { buildChatgptPlanRequest } from '../engines/provider-runtime/providerRuntimeChatgptPlanAdapter';
import { createProviderRuntimeTestContext, createProviderRuntimeTestProvider } from '../engines/provider-runtime/providerRuntimeFixtures';

const api = createProviderRuntimeTestProvider({ authKind: 'chatgpt-plan', protocol: 'openai-responses' });
function request() { return buildChatgptPlanRequest({ api, context: createProviderRuntimeTestContext({ withTools: false }) }); }
function chunk(id: string, text: string) {
  mocks.listener?.({ requestId: id, type: 'chunk', data: btoa(String.fromCharCode(...new TextEncoder().encode(text))) });
}
afterEach(() => { vi.clearAllMocks(); mocks.available = true; mocks.native = true; mocks.listener = null; });
describe('subscription transport isolation', () => {
  it('completes the full chat entry point with an empty API key', async () => {
    mocks.start.mockImplementation(async ({ requestId }) => {
      chunk(requestId, 'data: {"type":"response.output_text.delta","delta":"无需密钥"}\n\n');
      chunk(requestId, 'data: {"type":"response.completed","response":{"output":[]}}\n\n');
      mocks.listener?.({ requestId, type: 'complete' });
      return { status: 200, contentType: 'text/event-stream' };
    });
    const reply = await requestAssistantReply({ api: { ...api, apiKey: '' }, context: createProviderRuntimeTestContext({ withTools: false }) });
    expect(reply.content).toBe('无需密钥'); expect(mocks.genericStart).not.toHaveBeenCalled();
  });
  it('sends only the body and correlation ID to the dedicated native bridge and consumes pre-header chunks', async () => {
    mocks.start.mockImplementation(async ({ requestId }) => {
      chunk(requestId, 'data: {"type":"response.output_text.delta","delta":"你好"}\n\n');
      chunk(requestId, 'data: {"type":"response.completed","response":{"output":[]}}\n\n');
      mocks.listener?.({ requestId, type: 'complete' });
      return { status: 200, contentType: 'text/event-stream' };
    });
    expect((await executeBuiltRequest({ api, request: request() })).content).toBe('你好');
    const sent = mocks.start.mock.calls[0][0];
    expect(Object.keys(sent).sort()).toEqual(['body', 'requestId']);
    expect(JSON.stringify(sent)).not.toContain(api.apiKey); expect(mocks.genericStart).not.toHaveBeenCalled();
    expect(mocks.remove).toHaveBeenCalledOnce();
  });
  it('rejects truncated native streams and retains the last progress update', async () => {
    mocks.start.mockImplementation(async ({ requestId }) => {
      chunk(requestId, 'data: {"type":"response.output_text.delta","delta":"部分回复"}\n\n');
      mocks.listener?.({ requestId, type: 'complete' });
      return { status: 200, contentType: 'text/event-stream' };
    });
    const progress = vi.fn();
    await expect(executeBuiltRequest({ api, request: request(), onProgress: progress })).rejects.toThrow('连接中断');
    expect(progress).toHaveBeenCalledWith(expect.objectContaining({ content: '部分回复' }));
  });
  it('preserves a classified native connection failure without retrying or switching routes', async () => {
    const message = 'ChatGPT 在建立连接时失败：安全连接未能完成。本地聊天仍保留。（CGP-TLS-CONNECT）';
    mocks.start.mockRejectedValueOnce(new Error(message));
    await expect(executeBuiltRequest({ api, request: request() })).rejects.toThrow(message);
    expect(mocks.start).toHaveBeenCalledOnce();
    expect(mocks.genericStart).not.toHaveBeenCalled();
    expect(mocks.remove).toHaveBeenCalledOnce();
  });
  it('keeps partial text and a classified stream failure without silently replaying a request', async () => {
    const message = 'ChatGPT 在接收回复时失败：网络连接中断。本地聊天仍保留。（CGP-INTERRUPTED-STREAM）';
    mocks.start.mockImplementationOnce(async ({ requestId }) => {
      chunk(requestId, 'data: {"type":"response.output_text.delta","delta":"已收到的部分"}\n\n');
      mocks.listener?.({ requestId, type: 'error', message });
      return { status: 200, contentType: 'text/event-stream' };
    });
    const progress = vi.fn();
    await expect(executeBuiltRequest({ api, request: request(), onProgress: progress })).rejects.toThrow(message);
    expect(progress).toHaveBeenCalledWith(expect.objectContaining({ content: '已收到的部分' }));
    expect(mocks.start).toHaveBeenCalledOnce();
    expect(mocks.genericStart).not.toHaveBeenCalled();
  });
  it.each(['web', 'missing-bridge', 'forced-relay'])('never falls through to fetch or API-key transport: %s', async mode => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    mocks.native = mode !== 'web'; mocks.available = mode !== 'missing-bridge';
    try {
      await expect(executeBuiltRequest({ api, request: request(), forceRelay: mode === 'forced-relay' })).rejects.toThrow('不会自动切换');
      expect(fetch).not.toHaveBeenCalled(); expect(mocks.genericStart).not.toHaveBeenCalled();
    } finally { vi.unstubAllGlobals(); }
  });
});

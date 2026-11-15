import { afterEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ native: true, available: true, request: vi.fn(), open: vi.fn(), generic: vi.fn() }));
vi.mock('@capacitor/core', () => ({ Capacitor: { isNativePlatform: () => mocks.native, getPlatform: () => mocks.native ? 'android' : 'web', isPluginAvailable: () => mocks.available }, CapacitorHttp: { request: mocks.generic }, registerPlugin: () => ({ request: mocks.request, open: mocks.open }) }));
import { originalHome, originalHomeOrigin } from './originalHome';
import { clearMcpToolCatalogCacheForTests, invokeMcpTool, resolveMcpToolCatalog } from '../engines/mcpRuntime';
import { normalizeMcpServer, parseMcpServersJson, serializeMcpServersToJson } from '../stores/runtimeStoreMcp';
import type { McpServerConfig } from '../types/domain';
const server: McpServerConfig = { id: 'unit-home', handle: 'home', name: 'Home', description: '', url: 'https://unit.chatgpt.site/mcp', authKind: 'original-home-session', transport: 'streamable-http', isActive: true, headers: [] };
afterEach(() => { mocks.native = true; mocks.available = true; vi.clearAllMocks(); vi.unstubAllGlobals(); clearMcpToolCatalogCacheForTests(); });
describe('original website session bridge', () => {
  it('accepts only the original Site homepage or its MCP endpoint', () => {
    expect(originalHomeOrigin('https://unit.chatgpt.site/')).toBe('https://unit.chatgpt.site');
    expect(originalHomeOrigin(server.url)).toBe('https://unit.chatgpt.site');
    for (const url of ['http://unit.chatgpt.site', 'https://unit.chatgpt.site.evil.invalid', 'https://u:p@unit.chatgpt.site', 'https://unit.chatgpt.site/?token=unit', 'https://unit.chatgpt.site/other']) expect(originalHomeOrigin(url)).toBe('');
  });
  it('uses the protected native path for discovery and actual life-record invocation without model-visible cookies', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    mocks.request.mockImplementation(async ({ body, method }) => {
      if (method === 'DELETE') return { status: 204, data: '' };
      const rpc = JSON.parse(body); if (rpc.method === 'notifications/initialized') return { status: 202, data: '' };
      const result = rpc.method === 'initialize' ? { protocolVersion: '2025-03-26', capabilities: { tools: {} } } : rpc.method === 'tools/list' ? { tools: [{ name: 'home_list', inputSchema: { type: 'object' } }] } : { content: [{ type: 'text', text: 'Synthetic verified owner record' }] };
      return { status: 200, data: JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }) };
    });
    const catalog = await resolveMcpToolCatalog({ servers: [server], timeoutSeconds: 1, retryDelaysMs: [] }); expect(catalog.errors).toEqual([]);
    const result = await invokeMcpTool({ tool: catalog.tools[0], argumentsObject: { limit: 1 }, timeoutSeconds: 1 }); expect(result.ok).toBe(true); if (!result.ok) throw new Error(result.error); expect(result.detailText).toContain('verified owner'); expect(fetch).not.toHaveBeenCalled(); expect(mocks.generic).not.toHaveBeenCalled();
    for (const [args] of mocks.request.mock.calls) { expect(args.url).toBe(server.url); expect(JSON.stringify(args)).not.toMatch(/Cookie|Authorization/); }
  });
  it.each(['web', 'missing-native'] as const)('does not fall back to anonymous transport when %s', async mode => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); mocks.native = mode !== 'web'; mocks.available = false;
    await expect(originalHome.open({ url: 'https://unit.chatgpt.site' })).rejects.toThrow('Android');
    const catalog = await resolveMcpToolCatalog({ servers: [server], timeoutSeconds: 1, retryDelaysMs: [] }); expect(catalog.tools).toEqual([]); expect(fetch).not.toHaveBeenCalled(); expect(mocks.generic).not.toHaveBeenCalled();
  });
  it('exports public service configuration but excludes credential headers', () => {
    const normalized = normalizeMcpServer({ ...server, headers: [{ id: 'cookie', key: 'Cookie', value: 'unit-private-cookie' }] }); expect(normalized.headers).toEqual([]);
    const text = serializeMcpServersToJson([normalized]); expect(text).not.toContain('unit-private-cookie'); expect(parseMcpServersJson(text)[0].authKind).toBe('original-home-session');
  });
});

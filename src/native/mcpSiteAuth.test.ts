import { afterEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ native: true, available: true, request: vi.fn(), generic: vi.fn() }));
vi.mock('@capacitor/core', () => ({
  Capacitor: { isNativePlatform: () => mocks.native, getPlatform: () => mocks.native ? 'android' : 'web', isPluginAvailable: () => mocks.available },
  CapacitorHttp: { request: mocks.generic }, registerPlugin: () => ({ request: mocks.request })
}));
import { clearMcpToolCatalogCacheForTests, invokeMcpTool, resolveMcpToolCatalog } from '../engines/mcpRuntime';
import { normalizeMcpServer, parseMcpServersJson, serializeMcpServersToJson } from '../stores/runtimeStoreMcp';
import { buildServerHeaders } from '../engines/mcpRuntimeTransport';
import type { McpServerConfig } from '../types/domain';

const server: McpServerConfig = { id: 'mcp-unit', handle: 'memory', name: 'Memory', description: '', transport: 'streamable-http',
  url: 'https://unit.chatgpt.site/mcp', headers: [], authKind: 'sites-memory-oauth', isActive: true };
afterEach(() => { vi.clearAllMocks(); vi.unstubAllGlobals(); mocks.native = true; mocks.available = true; clearMcpToolCatalogCacheForTests(); });

describe('native Site MCP isolation', () => {
  it('discovers and calls through the protected bridge without credential headers or fetch', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    mocks.request.mockImplementation(async ({ body }) => {
      const rpc = JSON.parse(body);
      if (rpc.method === 'notifications/initialized') return { status: 202, data: '' };
      const result = rpc.method === 'initialize' ? { protocolVersion: '2026-07-28', capabilities: { tools: {} } }
        : rpc.method === 'tools/list' ? { tools: [{ name: 'memory_recall', description: 'Read', inputSchema: { type: 'object' } }] }
          : { content: [{ type: 'text', text: 'source-backed unit memory' }], structuredContent: { receipt_id: 'unit-read' } };
      return { status: 200, data: JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }) };
    });
    const catalog = await resolveMcpToolCatalog({ servers: [server], timeoutSeconds: 1, retryDelaysMs: [] });
    expect(catalog.errors).toEqual([]); expect(catalog.tools).toHaveLength(1);
    const result = await invokeMcpTool({ tool: catalog.tools[0], argumentsObject: { query: 'unit' }, timeoutSeconds: 1 });
    expect(result.ok).toBe(true);
    for (const [sent] of mocks.request.mock.calls) expect(Object.keys(sent).sort()).toEqual(['body', 'method', 'serverId', 'url']);
    expect(fetch).not.toHaveBeenCalled(); expect(mocks.generic).not.toHaveBeenCalled();
  });
  it.each(['web', 'missing-bridge', 'legacy-sse'])('fails closed without anonymous fallback: %s', async mode => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    mocks.native = mode !== 'web'; mocks.available = mode !== 'missing-bridge';
    const catalog = await resolveMcpToolCatalog({ servers: [{ ...server, transport: mode === 'legacy-sse' ? 'sse' : 'streamable-http' }], timeoutSeconds: 1, retryDelaysMs: [] });
    expect(catalog.errors).toHaveLength(1); expect(catalog.tools).toEqual([]);
    expect(fetch).not.toHaveBeenCalled(); expect(mocks.generic).not.toHaveBeenCalled(); expect(mocks.request).not.toHaveBeenCalled();
  });
  it('exports only a credential-free marker and refuses unknown imported auth modes', () => {
    const unsafe = { ...server, headers: [{ id: 'unit', key: 'Authorization', value: 'private-unit-sentinel' }] };
    const normalized = normalizeMcpServer(unsafe);
    expect(normalized.headers).toEqual([]);
    expect(buildServerHeaders(unsafe).has('Authorization')).toBe(false);
    const exported = serializeMcpServersToJson([unsafe]);
    expect(exported).not.toContain('private-unit-sentinel');
    expect(parseMcpServersJson(exported)[0]).toMatchObject({ authKind: 'sites-memory-oauth', headers: [] });
    expect(() => parseMcpServersJson('{"mcpServers":{"unit":{"url":"https://unit.chatgpt.site/mcp","authKind":"unknown-oauth"}}}')).toThrow('未降级');
  });
});

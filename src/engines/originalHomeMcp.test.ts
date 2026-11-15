import { beforeEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ catalog: vi.fn(), invoke: vi.fn() }));
vi.mock('./mcpRuntime', () => ({ resolveMcpToolCatalog: mocks.catalog, invokeMcpTool: mocks.invoke }));
import { connectOriginalHome } from './originalHomeMcp';
import { normalizeMcpServer } from '../stores/runtimeStoreMcp';
describe('original home account verification', () => {
  beforeEach(() => { vi.clearAllMocks(); mocks.catalog.mockResolvedValue({ errors: [], tools: ['home_list', 'mood_save'].map(toolName => ({ toolName, description: 'unit', inputSchema: { type: 'object' } })) }); mocks.invoke.mockResolvedValue({ ok: true, isError: false }); });
  it('enables only after a real read succeeds and leaves other memory connections intact', async () => {
    const memory = normalizeMcpServer({ id: 'memory', url: 'https://mcp.notion.com/mcp', authKind: 'notion-memory-oauth' });
    const store = { mcpServers: [memory], createMcpServer: vi.fn().mockReturnValue('home'), updateMcpServer: vi.fn() };
    expect(await connectOriginalHome(() => store, 'https://unit.chatgpt.site/', () => true)).toBe('home'); expect(mocks.invoke.mock.calls[0][0].argumentsObject).toEqual({ limit: 1 }); expect(store.mcpServers).toEqual([memory]); expect(store.createMcpServer.mock.calls[0][0]).toMatchObject({ authKind: 'original-home-session', headers: [], isActive: true });
  });
  it('retains prior disabled tool choices and reuses the source rather than adding duplicates', async () => {
    const existing = normalizeMcpServer({ id: 'home', url: 'https://unit.chatgpt.site/mcp', authKind: 'original-home-session', tools: [{ name: 'mood_save', description: '', inputSchema: {}, enabled: false }] });
    const store = { mcpServers: [existing], createMcpServer: vi.fn(), updateMcpServer: vi.fn() };
    expect(await connectOriginalHome(() => store, 'https://unit.chatgpt.site/', () => true)).toBe('home'); expect(store.createMcpServer).not.toHaveBeenCalled(); expect(store.updateMcpServer.mock.calls[0][1].tools[1].enabled).toBe(false);
  });
  it('does not claim connected or enable writes for a visible catalog with a failed owner read', async () => {
    const store = { mcpServers: [], createMcpServer: vi.fn(), updateMcpServer: vi.fn() };
    mocks.invoke.mockResolvedValue({ ok: false }); await expect(connectOriginalHome(() => store, 'https://unit.chatgpt.site/', () => true)).rejects.toThrow('未启用'); expect(store.createMcpServer).not.toHaveBeenCalled(); expect(store.updateMcpServer).not.toHaveBeenCalled();
    await expect(connectOriginalHome(() => store, 'https://unit.chatgpt.site/', () => false)).rejects.toThrow('窗口已切换');
  });
});

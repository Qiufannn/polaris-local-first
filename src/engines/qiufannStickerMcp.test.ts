import { beforeEach, describe, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ catalog: vi.fn() }));
vi.mock('./mcpRuntime', () => ({ resolveMcpToolCatalog: mocks.catalog }));
import { connectQiufannStickerMcp, QIUFANN_STICKER_MCP } from './qiufannStickerMcp';
import { normalizeMcpServer } from '../stores/runtimeStoreMcp';
describe('sticker chat connection', () => {
  const tools = ['find_azhou_stickers', 'send_azhou_sticker_image', 'unexpected_mutation'].map(toolName => ({ toolName, description: 'unit', inputSchema: { type: 'object' } }));
  beforeEach(() => { vi.clearAllMocks(); mocks.catalog.mockResolvedValue({ tools, errors: [] }); });
  it('only adds the sticker connection and enables the two required tools after discovery succeeds', async () => {
    const memory = normalizeMcpServer({ id: 'memory', url: 'https://mcp.notion.com/mcp', authKind: 'notion-memory-oauth' });
    const store = { mcpServers: [memory], createMcpServer: vi.fn().mockReturnValue('stickers'), updateMcpServer: vi.fn() };
    expect(await connectQiufannStickerMcp(() => store, () => true)).toBe('stickers'); expect(store.mcpServers).toEqual([memory]);
    expect(store.createMcpServer).toHaveBeenCalledWith(expect.objectContaining({ url: QIUFANN_STICKER_MCP, headers: [], isActive: true }));
    expect(store.createMcpServer.mock.calls[0][0].tools.map((t: { enabled: boolean }) => t.enabled)).toEqual([true, true, false]);
  });
  it('reuses an existing server without replacing headers or memory configs', async () => {
    const existing = normalizeMcpServer({ id: 'existing', url: QIUFANN_STICKER_MCP });
    const store = { mcpServers: [existing], createMcpServer: vi.fn(), updateMcpServer: vi.fn() };
    expect(await connectQiufannStickerMcp(() => store, () => true)).toBe('existing'); expect(store.createMcpServer).not.toHaveBeenCalled();
    expect(store.updateMcpServer.mock.calls[0][1].headers).toBeUndefined();
  });
  it('does not write settings for a failed connection or a switched window', async () => {
    const store = { mcpServers: [], createMcpServer: vi.fn(), updateMcpServer: vi.fn() };
    await expect(connectQiufannStickerMcp(() => store, () => false)).rejects.toThrow('窗口已切换');
    mocks.catalog.mockResolvedValue({ tools: [], errors: ['unavailable'] });
    await expect(connectQiufannStickerMcp(() => store, () => true)).rejects.toThrow('本机表情包面板'); expect(store.createMcpServer).not.toHaveBeenCalled(); expect(store.updateMcpServer).not.toHaveBeenCalled();
  });
});

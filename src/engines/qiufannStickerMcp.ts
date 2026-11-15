import type { McpServerConfig } from '../types/domain';
import { normalizeMcpServer } from '../stores/runtimeStoreMcp';
import { resolveMcpToolCatalog } from './mcpRuntime';

export const QIUFANN_STICKER_MCP = 'https://azhou-stickers.onrender.com/mcp';
type Store = {
  mcpServers: McpServerConfig[];
  createMcpServer: (seed: Partial<McpServerConfig>) => string;
  updateMcpServer: (id: string, patch: Partial<McpServerConfig>) => void;
};
export async function connectQiufannStickerMcp(getState: () => Store, isCurrent: () => boolean) {
  const existing = getState().mcpServers.find(server => server.url === QIUFANN_STICKER_MCP);
  const candidate = existing ?? normalizeMcpServer({ id: 'qiufann-stickers-probe', handle: 'azhou-stickers', name: '哥哥的表情包', url: QIUFANN_STICKER_MCP, transport: 'streamable-http', headers: [], isActive: true });
  const catalog = await resolveMcpToolCatalog({ servers: [{ ...candidate, isActive: true }], timeoutSeconds: 45, retryDelaysMs: [], useCachedOnFailure: false, includeDisabledTools: true });
  if (!isCurrent()) throw new Error('窗口已切换，本次没有改变表情包连接。');
  if (catalog.errors.length || !['find_azhou_stickers', 'send_azhou_sticker_image'].every(name => catalog.tools.some(tool => tool.toolName === name))) throw new Error('表情包聊天服务暂时没有返回完整工具；你仍可使用本机表情包面板。');
  const tools = catalog.tools.map(tool => ({ name: tool.toolName, description: tool.description, inputSchema: tool.inputSchema,
    enabled: ['find_azhou_stickers', 'send_azhou_sticker_image'].includes(tool.toolName) }));
  // Re-read after discovery: retain all memory connections and avoid duplicate servers.
  const latest = getState().mcpServers.find(server => server.url === QIUFANN_STICKER_MCP);
  if (latest) { getState().updateMcpServer(latest.id, { isActive: true, tools }); return latest.id; }
  return getState().createMcpServer({ handle: 'azhou-stickers', name: '哥哥的表情包', url: QIUFANN_STICKER_MCP,
    transport: 'streamable-http', headers: [], isActive: true, tools });
}

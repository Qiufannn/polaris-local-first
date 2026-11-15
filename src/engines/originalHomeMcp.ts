import type { McpServerConfig } from '../types/domain';
import { normalizeMcpServer } from '../stores/runtimeStoreMcp';
import { invokeMcpTool, resolveMcpToolCatalog } from './mcpRuntime';
import { originalHomeEndpoint, originalHomeOrigin } from '../native/originalHome';
type Store = { mcpServers: McpServerConfig[]; createMcpServer: (seed: Partial<McpServerConfig>) => string; updateMcpServer: (id: string, patch: Partial<McpServerConfig>) => void };
export async function connectOriginalHome(getState: () => Store, homeUrl: string, isCurrent: () => boolean) {
  const origin = originalHomeOrigin(homeUrl); if (!origin) throw new Error('原小屋地址无效。');
  const url = `${origin}/mcp`, existing = getState().mcpServers.find(server => originalHomeEndpoint(server.url) === url && server.authKind === 'original-home-session');
  const candidate = existing ? { ...existing, url } : normalizeMcpServer({ id: 'original-home-probe', name: '原小屋', url, transport: 'streamable-http', authKind: 'original-home-session', isActive: false, headers: [] });
  const catalog = await resolveMcpToolCatalog({ servers: [{ ...candidate, isActive: true }], timeoutSeconds: 30, retryDelaysMs: [], useCachedOnFailure: false, includeDisabledTools: true });
  if (!isCurrent()) throw new Error('窗口已切换，未改变原小屋连接。');
  const read = catalog.tools.find(tool => tool.toolName === 'home_list');
  if (catalog.errors.length || !read) throw new Error('原小屋工具未连接。网页可以继续使用，请在请求 → MCP 核对登录后测试。');
  const result = await invokeMcpTool({ tool: read, argumentsObject: { limit: 1 }, timeoutSeconds: 30 });
  if (!isCurrent()) throw new Error('窗口已切换，未改变原小屋连接。');
  if (!result.ok || result.isError) throw new Error('原小屋记录尚未读取成功，未启用 AI 写入。');
  const latest = getState().mcpServers.find(server => originalHomeEndpoint(server.url) === url && server.authKind === 'original-home-session');
  const tools = catalog.tools.map(tool => ({ name: tool.toolName, description: tool.description, inputSchema: tool.inputSchema, enabled: latest?.tools?.find(item => item.name === tool.toolName)?.enabled ?? true }));
  if (latest) { getState().updateMcpServer(latest.id, { url, tools, isActive: true, headers: [] }); return latest.id; }
  return getState().createMcpServer({ name: '原小屋', url, authKind: 'original-home-session', transport: 'streamable-http', headers: [], isActive: true, tools });
}

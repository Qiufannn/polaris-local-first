import { useEffect, useRef, useState } from 'react';
import type { McpServerConfig, McpServerToolConfig } from '../../../types/domain';
import { canUseMemorySources, memorySources, type MemoryAddress } from '../../../native/memorySources';
import { invokeMcpTool, resolveMcpToolCatalog } from '../../../engines/mcpRuntime';

export function GardenConnection({ server, onReady, onEnable, onTools }: {
  server: McpServerConfig | null; onReady: (value: boolean) => void; onEnable: () => void; onTools: (tools: McpServerToolConfig[]) => void;
}) {
  const [connected, setConnected] = useState(false), [busy, setBusy] = useState(false), [message, setMessage] = useState('');
  const version = useRef(0), login = useRef(false);
  const key = server ? `${server.id}|${server.url}|${server.authKind}` : '';
  const address: MemoryAddress | null = server?.authKind === 'garden-oauth' ? { serverId: server.id, url: server.url, authKind: 'garden-oauth' } : null;
  useEffect(() => {
    const stamp = ++version.current; setConnected(false); setBusy(false); setMessage(''); onReady(false);
    if (address && canUseMemorySources()) void memorySources.status(address).then(status => { if (stamp === version.current) setConnected(status.connected); }).catch(() => { if (stamp === version.current) setMessage('尚未读取到花园连接，请在此页登录。'); });
    return () => { version.current++; if (login.current && address) void memorySources.cancel(address).catch(() => undefined); };
  }, [key, onReady]);
  async function run(action: (valid: () => boolean) => Promise<void>) {
    const stamp = version.current; setBusy(true); setMessage('');
    try { await action(() => stamp === version.current); } catch (error) { if (stamp === version.current) setMessage(error instanceof Error ? error.message : '花园连接尚未完成。'); }
    finally { if (stamp === version.current) { setBusy(false); login.current = false; } }
  }
  return <section className="mcp-test-panel" aria-label="花园网页登录">
    <strong>花园 · 原账号连接</strong>
    <p>在花园官方网页批准连接，选择原有的小机。连接凭证仅由本机安全保存；不粘贴到聊天、请求头或导出文件。既有 ChatGPT 花园连接不会被替换。</p>
    {!canUseMemorySources() ? <p>请使用包含花园网页登录的 Android 更新版。</p> : !address ? <p>先保存这张服务卡，再重新打开登录。</p> : <>
      <p>{connected ? '已有本机花园授权，请核对账号身份。' : '尚未连接花园。'}</p>
      <div className="provider-inline-actions">
        <button type="button" className="mcp-btn secondary" disabled={busy} onClick={() => void run(async valid => {
          login.current = true;
          const status = await memorySources.notionLogin(address); if (!valid()) return;
          setConnected(status.connected); onReady(false); if (status.connected) setMessage('官方授权已完成。请点“核对花园身份并同步工具”，确认是原来的小机，再保存。');
        })}>{connected ? '重新登录花园' : '连接花园（官方网页登录）'}</button>
        {busy && login.current && <button type="button" className="mcp-btn secondary" onClick={() => { version.current++; login.current = false; setBusy(false); void memorySources.cancel(address).catch(() => undefined); }}>取消登录</button>}
        {connected && <button type="button" className="mcp-btn secondary" disabled={busy} onClick={() => void run(async valid => {
          const catalog = await resolveMcpToolCatalog({ servers: [{ ...server!, isActive: true }], timeoutSeconds: 45, retryDelaysMs: [], useCachedOnFailure: false, includeDisabledTools: true });
          if (!valid()) return; if (catalog.errors.length) throw new Error('花园工具目录暂未读取成功，请稍后重试，不用改其他连接。');
          const self = catalog.tools.find(tool => tool.toolName === 'get_self'); if (!self) throw new Error('花园未提供身份核对工具，尚未启用。');
          const result = await invokeMcpTool({ tool: self, argumentsObject: {}, timeoutSeconds: 45 }); if (!valid()) return;
          if (!result.ok || result.isError) throw new Error('花园身份读取未完成，尚未确认原账号。');
          let profile: unknown = result.structuredContent;
          if (!profile || typeof profile !== 'object' || !('machine' in profile)) { try { profile = JSON.parse(result.detailText); } catch { throw new Error('花园身份格式未识别，尚未确认账号。'); } }
          const identity = profile as { machine?: { id?: number; name?: string }; human?: { name?: string } };
          if (!identity.machine?.id || !identity.machine.name) throw new Error('花园没有返回可核对的小机身份，尚未启用。');
          onTools(catalog.tools.map(tool => ({ name: tool.toolName, description: tool.description, inputSchema: tool.inputSchema, enabled: true })));
          onReady(true); onEnable(); setMessage(`花园返回：${identity.machine.name}（编号 ${identity.machine.id}）${identity.human?.name ? ` · ${identity.human.name}` : ''}。请核对是原账号后保存；本次只读身份，没有发帖、回复或启动游戏。`);
        })}>核对花园身份并同步工具</button>}
        {connected && <button type="button" className="mcp-btn secondary" disabled={busy} onClick={() => void run(async valid => { await memorySources.disconnect(address); if (!valid()) return; setConnected(false); onReady(false); setMessage('已清除本机花园连接；远端撤销状态未确认。'); })}>断开本机花园连接</button>}
      </div>
    </>}
    {busy && <p role="status">{login.current ? '等待花园官方网页授权，完成后返回北极星…' : '正在核对花园连接…'}</p>}
    {message && <p role="status">{message}</p>}
  </section>;
}

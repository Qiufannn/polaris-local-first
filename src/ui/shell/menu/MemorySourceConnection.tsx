import { useEffect, useRef, useState } from 'react';
import type { McpServerConfig, McpServerToolConfig } from '../../../types/domain';
import { canUseMemorySources, memorySources, type MemoryAddress } from '../../../native/memorySources';
import { invokeMcpTool, resolveMcpToolCatalog } from '../../../engines/mcpRuntime';
import { writeTextToClipboard } from '../../../infrastructure/clipboard';

export function MemorySourceConnection({ server, onReady, onEnable, onTools }: {
  server: McpServerConfig | null; onReady: (value: boolean) => void; onEnable: () => void; onTools: (tools: McpServerToolConfig[]) => void;
}) {
  const [connected, setConnected] = useState(false), [busy, setBusy] = useState(''), [message, setMessage] = useState(''), [code, setCode] = useState('');
  const version = useRef(0), activeLogin = useRef(false);
  const available = canUseMemorySources();
  const key = server ? JSON.stringify([server.id, server.authKind, server.url, server.publicClientId]) : '';
  const address: MemoryAddress | null = server && (server.authKind === 'notion-memory-oauth' || server.authKind === 'github-memory-device')
    ? { serverId: server.id, authKind: server.authKind, url: server.url, publicClientId: server.publicClientId } : null;
  const github = address?.authKind === 'github-memory-device';

  useEffect(() => {
    const stamp = ++version.current; setConnected(false); onReady(false); setMessage(''); setCode(''); setBusy(''); activeLogin.current = false;
    if (available && address) void memorySources.status(address).then(status => {
      if (stamp !== version.current) return; setConnected(status.connected); onReady(status.connected);
      if (status.pending && status.userCode) { setCode(status.userCode); setMessage('还有一次待接收的 GitHub 授权；若网页已批准，点“继续接收 GitHub 授权结果”，无需再次批准。'); }
    }).catch(error => { if (stamp === version.current) setMessage(error instanceof Error ? error.message : '无法读取本机连接。'); });
    return () => { version.current++; if (activeLogin.current && address) void memorySources.cancel(address).catch(() => undefined); };
  }, [key, available, onReady]);

  async function run(kind: string, action: (valid: () => boolean) => Promise<void>) {
    const stamp = version.current, valid = () => stamp === version.current;
    setBusy(kind); setMessage(''); activeLogin.current = kind === 'login';
    try { await action(valid); } catch (error) {
      if (valid()) setMessage(error instanceof Error ? error.message : '记忆来源连接未完成。');
      if (kind === 'login' && github && address) {
        try { const status = await memorySources.status(address); if (valid() && !status.pending) setCode(''); } catch { /* Preserve the original safe error. */ }
      }
    }
    finally { if (valid()) { setBusy(''); activeLogin.current = false; } }
  }
  async function connectedNow() { setConnected(true); onReady(true); onEnable(); setCode(''); setMessage('登录完成，请验证真实内容后保存。'); }

  return <section className="mcp-test-panel" aria-label="记忆来源授权">
    <strong>{server?.authKind === 'github-memory-device' ? 'GitHub 主记忆' : 'Notion 日记与补充资料'}</strong>
    <p>先验证读取，凭证只放在原生安全存储。这一步不自动上传聊天，也不修改记忆文件；GitHub 的新增和更正接在读取验证之后。</p>
    {!available ? <p>请安装包含这两个入口的 Android 更新版。</p> : !address ? <p>填好后先保存，再重新打开服务卡连接。</p> : <>
      <p>{connected ? '已有本机授权，实际内容仍需验证。' : '尚未连接。'}</p>
      <div className="provider-inline-actions">
        <button type="button" className="mcp-btn secondary" disabled={Boolean(busy)} onClick={() => void run('login', async valid => {
          if (github) {
            const started = await memorySources.githubStart(address); if (!valid()) return; setCode(started.userCode);
            setMessage('在下面的 GitHub 官方页面输入此验证码；不要把验证码发到聊天。');
            let seconds = started.pollAfterSeconds;
            while (valid()) {
              await new Promise(resolve => setTimeout(resolve, Math.max(5, seconds) * 1000)); if (!valid()) return;
              const result = await memorySources.githubPoll(address); if (!valid()) return;
              if (result.state === 'connected') { await connectedNow(); return; } seconds = result.pollAfterSeconds;
              setMessage(result.message || '仍在等待 GitHub 返回授权结果；网页已批准的话，保持此页即可，不用再次授权。');
            }
          } else { const status = await memorySources.notionLogin(address); if (valid() && status.connected) await connectedNow(); }
        })}>{github ? code && !busy ? '继续接收 GitHub 授权结果' : '连接 GitHub（设备登录）' : '连接 Notion（网页登录）'}</button>
        {busy === 'login' && <button type="button" className="mcp-btn secondary" onClick={() => {
          version.current++; activeLogin.current = false; setBusy(''); setCode(''); void memorySources.cancel(address).catch(() => undefined);
        }}>取消登录</button>}
        {connected && <button type="button" className="mcp-btn secondary" disabled={Boolean(busy)} onClick={() => void run('verify', async valid => {
          const catalog = await resolveMcpToolCatalog({ servers: [{ ...server!, isActive: true }], timeoutSeconds: 45, retryDelaysMs: [], includeDisabledTools: true });
          if (!valid()) return;
          if (catalog.errors.length) throw new Error(catalog.errors.join('\n'));
          onTools(catalog.tools.map(tool => ({ name: tool.toolName, description: tool.description, inputSchema: tool.inputSchema, enabled: true })));
          const tool = catalog.tools.find(item => item.toolName === (github ? 'github_memory_read' : 'notion-fetch'));
          if (!tool) throw new Error('未发现受支持的只读工具，尚未验证内容。');
          const properties = tool.inputSchema.properties as Record<string, unknown> | undefined;
          const root = server?.memoryRootUrl?.trim();
          if (!github && !root) throw new Error('请先填写并保存 Notion 日记入口页面链接。');
          const args = github ? { path: 'continuity/identity.md' } : properties?.id ? { id: root } : properties?.url ? { url: root } : null;
          if (!args) throw new Error('Notion 读取工具参数未识别，尚未调用。');
          const result = await invokeMcpTool({ tool, argumentsObject: args, timeoutSeconds: 45 }); if (!valid()) return;
          if (!result.ok || result.isError) throw new Error(result.ok ? '来源返回读取错误，尚未验证内容。' : result.error);
          if (github) {
            const data = result.structuredContent as { path?: string; blob_sha?: string; text?: string } | undefined;
            if (!data?.blob_sha || !data.text || data.path !== 'continuity/identity.md') throw new Error('未取得真实文件与版本 SHA。');
            setMessage(`已读到 ${data.path}，版本 SHA：${data.blob_sha}。请核对开头：${data.text.slice(0, 140)}。保存后即可在聊天里按需读取。`);
          } else {
            if (!result.detailText.trim()) throw new Error('Notion 未返回页面内容。');
            setMessage(`Notion 已返回页面内容，请核对标题和内容：${result.detailText.slice(0, 220)}。保存后即可在聊天里按需读取日记。`);
          }
          onEnable();
        })}>验证真实内容并同步工具</button>}
        {connected && <button type="button" className="mcp-btn secondary" disabled={Boolean(busy)} onClick={() => void run('disconnect', async valid => {
          await memorySources.disconnect(address); if (!valid()) return; setConnected(false); onReady(false); setMessage('已清除本机此来源连接，远端撤销未确认；可以在服务商的应用授权页移除此应用。');
        })}>断开本机连接</button>}
      </div>
      {code && <div><p>GitHub 验证码：<strong>{code}</strong></p><button type="button" className="mcp-btn secondary" onClick={() => void (async () => {
        await writeTextToClipboard(code); await memorySources.openGithub(); setMessage('请在 GitHub 官方页面粘贴验证码并批准，完成后返回 Polaris。');
      })().catch(() => setMessage('请手动复制此验证码，并在浏览器打开 https://github.com/login/device。'))}>复制验证码并打开 GitHub</button></div>}
    </>}
    {busy && <p role="status">{busy === 'login' ? '等待本人完成官方网页授权…' : '正在处理…'}</p>}
    {message && <p role="status">{message}</p>}
  </section>;
}

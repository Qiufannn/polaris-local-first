import { useEffect, useRef, useState } from 'react';
import { canUseMcpSiteAuth, mcpSiteAuth, type McpSiteAddress, type McpSiteStatus } from '../../../native/mcpSiteAuth';

export function McpSiteConnection({ address, onReady, onEnable }: {
  address: McpSiteAddress | null;
  onReady: (ready: boolean) => void;
  onEnable: () => void;
}) {
  const [status, setStatus] = useState<McpSiteStatus>({ accounts: [], activeRef: '' });
  const [busy, setBusy] = useState('');
  const [message, setMessage] = useState('');
  const version = useRef(0);
  const pendingLogin = useRef(false);
  const current = status.accounts.find(account => account.ref === status.activeRef);
  const connected = current?.connected === true && current.scopeGranted === true;
  const available = canUseMcpSiteAuth();
  const serverId = address?.serverId, url = address?.url;

  useEffect(() => {
    version.current++;
    pendingLogin.current = false; setBusy('');
    let cancelled = false;
    onReady(false); setMessage(''); setStatus({ accounts: [], activeRef: '' });
    if (available && serverId && url) {
      mcpSiteAuth.status({ serverId, url }).then(value => {
        if (cancelled) return;
        setStatus(value);
        const account = value.accounts.find(item => item.ref === value.activeRef);
        onReady(account?.connected === true && account.scopeGranted === true);
      }).catch(error => { if (!cancelled) setMessage(error instanceof Error ? error.message : '无法读取本机连接状态。'); });
    }
    return () => {
      cancelled = true; version.current++;
      if (pendingLogin.current) void mcpSiteAuth.cancelLogin().catch(() => undefined);
    };
  }, [available, serverId, url, onReady]);

  async function run(kind: string, action: (current: () => boolean) => Promise<void>) {
    const started = version.current;
    const current = () => started === version.current;
    pendingLogin.current = kind === 'login';
    setBusy(kind); setMessage('');
    try { await action(current); }
    catch (error) { if (current()) setMessage(error instanceof Error ? error.message : '小家连接未完成。'); }
    finally { if (current()) { pendingLogin.current = false; setBusy(''); } }
  }

  return <section className="mcp-test-panel" aria-label="小家独立授权">
    <strong>小家记忆：独立登录，只读</strong>
    <p>授权仅保存在本机原生安全存储，与模型订阅登录分开。仅开放记忆状态、接续包和检索；独立客户端授权与访问资格需在手机实测。</p>
    {!available ? <p>需要包含此功能的 Android 更新版。</p> : !address ? <p>先保存服务，再重新打开这张服务卡连接账号。</p> : <>
      <p>{connected ? '本机已有资源授权；实际记忆读取仍须验证。' : '尚未获得此资源的可用授权。'}</p>
      <div className="provider-inline-actions">
        <button type="button" className="mcp-btn secondary" disabled={Boolean(busy)} onClick={() => void run('login', async current => {
          const value = await mcpSiteAuth.login({ ...address, accountRef: status.activeRef || undefined });
          if (!current()) return;
          setStatus(value);
          const account = value.accounts.find(item => item.ref === value.activeRef);
          const ready = account?.connected === true && account.scopeGranted === true;
          onReady(ready);
          if (ready) { onEnable(); setMessage('登录完成。请验证接续包，确认站点接受此授权。'); }
          else setMessage('未授予此资源的调用权限，尚未接通记忆。');
        })}>Continue with ChatGPT</button>
        {busy === 'login' && <button type="button" className="mcp-btn secondary" onClick={() => void mcpSiteAuth.cancelLogin()}>取消登录</button>}
        <button type="button" className="mcp-btn secondary" disabled={!connected || Boolean(busy)} onClick={() => void run('verify', async current => {
          const stateResponse = await mcpSiteAuth.request({ ...address, method: 'POST', body: JSON.stringify({ jsonrpc: '2.0', id: 'verify-status', method: 'tools/call', params: { name: 'memory_status', arguments: {} } }) });
          if (!current()) return;
          const stateRpc = JSON.parse(stateResponse.data);
          if (stateRpc.error || stateRpc.result?.isError || stateRpc.result?.structuredContent?.connected !== true) throw new Error('服务未确认记忆中枢已连接，尚未读取接续包。');
          const response = await mcpSiteAuth.request({ ...address, method: 'POST', body: JSON.stringify({ jsonrpc: '2.0', id: 'verify-memory', method: 'tools/call', params: { name: 'memory_bootstrap', arguments: { window_kind: 'other' } } }) });
          if (!current()) return;
          const rpc = JSON.parse(response.data);
          if (rpc.error || rpc.result?.isError) throw new Error('服务未返回成功的接续包，尚未完成读取。');
          const packet = rpc.result?.structuredContent;
          const records: unknown[] = packet?.durable_records;
          const receipt = packet?.read_receipt?.receipt_id;
          if (!Array.isArray(records) || records.length === 0 || typeof receipt !== 'string' || !receipt) throw new Error('响应缺少真实记忆记录或读取回执，尚未完成验证。');
          const first = records[0] as { title?: unknown } | undefined;
          const title = typeof first?.title === 'string' ? ` 首条：${first.title}` : '';
          setMessage(`读取成功：${records.length} 条接续记录。回执：${receipt}。${title} 请保存服务后返回聊天。`);
          onEnable();
        })}>验证只读接续包</button>
        {connected && <button type="button" className="mcp-btn secondary" disabled={Boolean(busy)} onClick={() => void run('disconnect', async current => {
          const result = await mcpSiteAuth.disconnect(address);
          const next = await mcpSiteAuth.status(address);
          if (!current()) return;
          setStatus(next); onReady(false);
          setMessage(result.revocationConfirmed ? '已断开此资源授权。' : '已清除本机此资源连接；远端撤销尚未确认。');
        })}>断开此连接</button>}
      </div>
    </>}
    {busy && <p role="status">{busy === 'login' ? '请在系统浏览器完成本人授权，再返回 Polaris。' : '正在处理…'}</p>}
    {message && <p role="status">{message}</p>}
  </section>;
}

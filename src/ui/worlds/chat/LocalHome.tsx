import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useSpaceStore } from '../../../stores/spaceStore';
import { usePersonaStore } from '../../../stores/personaStore';
import { useRuntimeStore } from '../../../stores/runtimeStore';
import { normalizeHomeUrl } from '../../../engines/localHome';
import type { Persona, CollectionShelf } from '../../../types/domain';

export function LocalHome({ persona, onClose }: { persona: Persona | null; onClose: () => void }) {
  const [link, setLink] = useState(persona?.advanced.homeUrl ?? ''), [notice, setNotice] = useState('');
  const servers = useRuntimeStore(state => state.mcpServers);
  useEffect(() => {
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    document.addEventListener('keydown', escape); return () => document.removeEventListener('keydown', escape);
  }, [onClose]);
  const url = normalizeHomeUrl(persona?.advanced.homeUrl);
  const visibleServers = servers.filter(server => persona?.mcp.inheritGlobal !== false || persona.mcp.serverIds.includes(server.id));
  const configured = (kind: string) => visibleServers.some(server => server.authKind === kind && server.isActive);
  function openShelf(shelf: CollectionShelf) {
    const space = useSpaceStore.getState();
    if (persona) space.setFrontstageCollaboratorId(persona.id);
    space.setCollectionShelf(shelf); space.setWorld('collection'); onClose();
  }
  return createPortal(<div className="local-home-overlay"><section className="local-home-sheet" role="dialog" aria-modal="true" aria-label="我们的小家">
    <header><span>POLARIS · HOME</span><button type="button" aria-label="关闭家园" onClick={onClose}>×</button></header>
    <div className="local-home-welcome"><span aria-hidden="true">⌂</span><h2>我们的小家</h2><p>{persona?.name || 'Polaris'}，在这里接着聊。</p><time>{new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())}</time></div>
    <nav className="local-home-grid" aria-label="家园入口">
      <button type="button" onClick={onClose}><span>☏</span><strong>继续聊天</strong><small>回到刚才的对话</small></button>
      <button type="button" onClick={() => openShelf('info')}><span>✧</span><strong>房间与记忆</strong><small>身份、提示词、技能与连接</small></button>
      <button type="button" onClick={() => openShelf('image')}><span>▧</span><strong>相册</strong><small>保存过的图片</small></button>
      <button type="button" onClick={() => openShelf('code')}><span>❖</span><strong>小应用与作品</strong><small>已有作品和代码卡</small></button>
      <a href="https://galatea.abysslumina.com" target="_blank" rel="noopener noreferrer"><span>❀</span><strong>去花园</strong><small>打开花园官网</small></a>
      {url ? <a href={url} target="_blank" rel="noopener noreferrer"><span>♡</span><strong>原来的家园</strong><small>打开你保存的小家网页</small></a> : <button type="button" onClick={() => document.getElementById('local-home-url')?.focus()}><span>♡</span><strong>原来的家园</strong><small>先在下面填写网页地址</small></button>}
    </nav>
    <div className="local-home-connections"><strong>当前房间的连接配置</strong><p>GitHub 主记忆：{configured('github-memory-device') ? '已启用' : '未启用'} · Notion 日记：{configured('notion-memory-oauth') ? '已启用' : '未启用'} · 花园：{configured('garden-oauth') ? '已启用' : '未启用'}</p><small>这里显示配置状态；实际登录与可用性以工具请求为准。</small></div>
    {persona && <details className="local-home-link"><summary>设置原家园入口</summary><label htmlFor="local-home-url">原家园网页地址</label><input id="local-home-url" type="url" value={link} onChange={event => setLink(event.target.value)} placeholder="https://你原来的家园网址" /><button type="button" className="mcp-btn secondary" onClick={() => { const next = normalizeHomeUrl(link); if (link.trim() && !next) { setNotice('请填写不含账号密码的完整 HTTPS 网页地址。'); return; } usePersonaStore.getState().updateCollaborator(persona.id, { advanced: { homeUrl: next } }); setNotice(next ? '家园入口已保存。' : '已清除家园入口。'); }}>保存入口</button>{notice && <p role="status">{notice}</p>}</details>}
  </section></div>, document.body);
}

import { useEffect, useRef, useState } from 'react';
import type { Persona } from '../../types/domain';
import { canUseOriginalHome, originalHome, originalHomeOrigin } from '../../native/originalHome';
import { connectOriginalHome } from '../../engines/originalHomeMcp';
import { useRuntimeStore } from '../../stores/runtimeStore';
import { usePersonaStore } from '../../stores/personaStore';
import type { HomeDestination } from './useHomeScreenController';

/** Launches the unchanged private website; it does not draw a replacement home. */
export function HomeScreen({ persona, onEnter, onOpenSettings }: { persona: Persona | null; backgroundUrl?: string | null; onEnter: (destination: HomeDestination, notice?: string) => void; onOpenSettings: () => void }) {
  const [url, setUrl] = useState(persona?.advanced.homeUrl ?? ''), [busy, setBusy] = useState(false), [notice, setNotice] = useState('');
  const attempted = useRef(''), version = useRef(0), alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { setUrl(persona?.advanced.homeUrl ?? ''); }, [persona?.id, persona?.advanced.homeUrl]);
  const saved = originalHomeOrigin(persona?.advanced.homeUrl ?? '');
  const ownerKey = useRef(''); ownerKey.current = `${persona?.id ?? ''}|${saved}`;
  async function open(value: string) {
    const origin = originalHomeOrigin(value); if (!origin || !persona) { setNotice('请填写原小屋的 HTTPS 首页地址。'); return; }
    const stamp = ++version.current, owner = persona.id;
    const valid = () => alive.current && stamp === version.current && ownerKey.current === `${owner}|${origin}`;
    setBusy(true); setNotice('正在打开原小屋…');
    try {
      const result = await originalHome.open({ url: origin }); if (!valid()) return;
      if (result.action === 'chat') {
        let warning = '';
        try {
          const serverId = await connectOriginalHome(useRuntimeStore.getState, origin, valid);
          const current = usePersonaStore.getState().personas.find(item => item.id === owner);
          if (current?.mcp.inheritGlobal === false && !current.mcp.serverIds.includes(serverId)) usePersonaStore.getState().updateCollaborator(owner, { mcp: { serverIds: [...current.mcp.serverIds, serverId] } });
        } catch (error) { warning = error instanceof Error ? error.message : '原小屋 AI 工具尚未验证，网页仍可使用。'; }
        if (valid()) onEnter('chat', warning);
      } else setNotice('原小屋已关闭。可重新打开，或先进入北极星。');
    } catch (error) { if (alive.current && stamp === version.current) setNotice(error instanceof Error ? error.message : '原小屋暂未打开。'); }
    finally { if (alive.current && stamp === version.current) setBusy(false); }
  }
  useEffect(() => {
    if (saved && persona && canUseOriginalHome() && attempted.current !== `${persona.id}|${saved}`) { attempted.current = `${persona.id}|${saved}`; void open(saved); }
  }, [saved, persona?.id]);
  return <section className="original-home-launch" aria-label="原小屋启动">
    <h1>打开原来的小屋</h1><p>使用原网页完整的三页、头像和原有排版。首次需要在 App 内登录；登录内容不会发到聊天中。</p>
    {!saved && <label>原小屋首页地址<input type="url" aria-label="原小屋首页地址" value={url} onChange={event => setUrl(event.target.value)} placeholder="https://你的原小屋.chatgpt.site" /></label>}
    {!saved && <button type="button" disabled={busy || !persona} onClick={() => { const origin = originalHomeOrigin(url); if (!origin || !persona) { setNotice('请填写不含参数或密码的原小屋 HTTPS 首页地址。'); return; } usePersonaStore.getState().updateCollaborator(persona.id, { advanced: { homeUrl: origin + '/' } }); }}>保存并打开原小屋</button>}
    {saved && canUseOriginalHome() && <button type="button" disabled={busy} onClick={() => void open(saved)}>{busy ? '正在等待原小屋…' : '重新打开原小屋'}</button>}
    {saved && !canUseOriginalHome() && <a href={saved} target="_blank" rel="noopener noreferrer">在浏览器打开原小屋网页</a>}
    {notice && <p role="status">{notice}</p>}
    <button type="button" disabled={busy} onClick={() => onEnter('chat')}>先进入北极星聊天</button><button type="button" disabled={busy} onClick={onOpenSettings}>设置</button>
  </section>;
}

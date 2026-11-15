import { useEffect, useMemo, useRef, useState } from 'react';
import { qiufannStickers, searchQiufannStickers, stickerAssetPath, type QiufannSticker } from '../../../../engines/qiufannStickers';
import { connectQiufannStickerMcp } from '../../../../engines/qiufannStickerMcp';
import { useRuntimeStore } from '../../../../stores/runtimeStore';
import { usePersonaStore } from '../../../../stores/personaStore';

export function StickerPicker({ disabled, personaId, onPick }: {
  disabled: boolean; personaId: string | null; onPick: (sticker: QiufannSticker, signal: AbortSignal) => Promise<void>;
}) {
  const [query, setQuery] = useState(''), [picking, setPicking] = useState(''), [connecting, setConnecting] = useState(false), [status, setStatus] = useState('');
  const active = useRef(true), controller = useRef<AbortController | null>(null), busy = useRef(false);
  useEffect(() => { active.current = true; return () => { active.current = false; controller.current?.abort(); }; }, []);
  const stickers = useMemo(() => searchQiufannStickers(query), [query]);
  return <div className="sticker-picker">
    <label className="sticker-picker-search"><span>搜索表情包</span><input type="search" value={query} placeholder="名字、心情或标签，比如：开心、抱抱" onChange={event => setQuery(event.target.value)} /></label>
    <div className="sticker-picker-meta"><span>{query.trim() ? `找到 ${stickers.length} 张` : `${qiufannStickers.length} 张原图 · 选中后点发送`}</span>
      <button type="button" disabled={connecting || disabled} onClick={() => {
        if (connecting) return; setConnecting(true); setStatus('正在检查聊天表情包连接…');
        void connectQiufannStickerMcp(useRuntimeStore.getState, () => active.current).then(serverId => {
          if (!active.current) return;
          const store = usePersonaStore.getState(), persona = store.personas.find(entry => entry.id === personaId);
          if (persona?.mcp.inheritGlobal === false) store.updateCollaborator(persona.id, { mcp: { serverIds: Array.from(new Set([...persona.mcp.serverIds, serverId])) } });
          useRuntimeStore.getState().setToolPromptGroupEnabled('mcp', true);
          setStatus('聊天表情包已接通。你可以请哥哥挑一张表情包发来。');
        }).catch(error => { if (active.current) setStatus(error instanceof Error ? error.message : '聊天连接暂未完成，本机图库仍可用。'); })
          .finally(() => { if (active.current) setConnecting(false); });
      }}>{connecting ? '连接中…' : '让哥哥也能发表情包'}</button>
    </div>
    {status && <p className="sticker-picker-status" role="status">{status}</p>}
    <div className="sticker-picker-grid" aria-label="表情包图库">
      {stickers.map(sticker => <button key={sticker.id} type="button" className="sticker-picker-item" disabled={disabled || Boolean(picking)} aria-label={`选择表情包：${sticker.name}`} onClick={() => {
        if (busy.current || disabled) return; busy.current = true; setPicking(sticker.id); setStatus('');
        controller.current = new AbortController();
        void onPick(sticker, controller.current.signal).catch(error => { if (active.current) setStatus(error instanceof Error ? error.message : '表情包没有加入，请重试。'); })
          .finally(() => { busy.current = false; if (active.current) setPicking(''); });
      }}><img src={stickerAssetPath(sticker)} alt="" loading="lazy" draggable={false} /><span>{picking === sticker.id ? '正在加入…' : sticker.name}</span></button>)}
      {!stickers.length && <p className="sticker-picker-empty">没找到，换一个名字或心情试试。</p>}
    </div>
  </div>;
}

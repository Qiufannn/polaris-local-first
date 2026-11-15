import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { ChatMessageQuote } from '../../../../types/domain';

export function MessageQuote({ quote, onOpen, onRemove }: { quote: ChatMessageQuote; onOpen?: () => void; onRemove?: () => void }) {
  return <div className="message-quote-strip">
    <button type="button" className="message-quote-source" onClick={onOpen} disabled={!onOpen} aria-label={`查看引用原文：${quote.author}`}><strong>↩ {quote.author || (quote.role === 'assistant' ? '协作者' : '我')}</strong><span>{quote.text}</span></button>
    {onRemove && <button type="button" className="message-quote-remove" aria-label="取消消息引用" onClick={onRemove}>×</button>}
  </div>;
}
export function MessageQuoteAction({ content, onQuote }: { content: string; onQuote: (selection?: string) => void }) {
  const [open, setOpen] = useState(false), [selection, setSelection] = useState('');
  const button = useRef<HTMLButtonElement>(null), input = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (!open) return;
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    document.addEventListener('keydown', escape); return () => document.removeEventListener('keydown', escape);
  }, [open]);
  const start = () => {
    const selected = window.getSelection();
    const row = button.current?.closest('[data-message-id]');
    const selectedText = selected?.toString().trim() ?? '';
    if (selectedText && selected?.anchorNode && selected.focusNode && row?.contains(selected.anchorNode) && row.contains(selected.focusNode) && content.includes(selectedText)) { onQuote(selectedText); return; }
    setSelection(''); setOpen(true);
  };
  return <>
    <button ref={button} type="button" className="message-quote-action" aria-label="引用这条消息" onPointerDown={event => event.preventDefault()} onClick={start}>↩ 引用</button>
    {open && createPortal(<div className="quote-picker-overlay" onClick={event => { if (event.target === event.currentTarget) setOpen(false); }}><section role="dialog" aria-modal="true" aria-label="选择引用内容" className="quote-picker-sheet">
      <strong>引用消息</strong><p>可引用整条，也可在下面长按选中几句话。最多引用前 4000 字符。</p>
      <textarea ref={input} readOnly value={content} aria-label="可选择的原文" onSelect={() => { const field = input.current; if (field) setSelection(content.slice(field.selectionStart, field.selectionEnd).trim()); }} />
      <div className="local-skill-actions"><button type="button" className="mcp-btn secondary" onClick={() => { onQuote(); setOpen(false); }}>引用整条</button><button type="button" className="mcp-btn secondary" disabled={!selection} onClick={() => { onQuote(selection); setOpen(false); }}>引用选中内容</button><button type="button" className="mcp-btn secondary" onClick={() => setOpen(false)}>取消</button></div>
    </section></div>, document.body)}
  </>;
}

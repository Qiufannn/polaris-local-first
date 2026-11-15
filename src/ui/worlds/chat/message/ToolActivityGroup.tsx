import { useEffect, useState, type ReactNode } from 'react';
import type { ChatActivityDisplay, ChatMessage } from '../../../../types/domain';
import { useI18n } from '../../../../i18n';
import { Icon } from '../../../Icon';
import { needsActivityAttention } from '../timeline/messageTimelineActivity';

export function ToolActivityGroup({ messages, mode, forceOpen = false, children }: {
  messages: ChatMessage[]; mode: ChatActivityDisplay; forceOpen?: boolean; children: ReactNode;
}) {
  const { t } = useI18n();
  const attention = messages.some(needsActivityAttention);
  const running = messages.some(message => message.toolInvocation?.status === 'running');
  const [open, setOpen] = useState(attention || forceOpen);
  useEffect(() => { if (attention || forceOpen) setOpen(true); }, [attention, forceOpen]);
  if (mode === 'full') return <>{children}</>;
  if (mode === 'hidden' && !attention) return null;
  return <details className="tool-activity-group" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary className="tool-activity-summary">
      <Icon name="zap" size={15} />
      <span>{t('chat.activity.count', { count: messages.length })}</span>
      <small>{attention ? t('chat.activity.attention') : running ? t('chat.activity.running') : t('chat.activity.done')}</small>
      <span className="tool-activity-chevron"><Icon name="chevron" size={14} /></span>
    </summary>
    <div className="tool-activity-body">{open ? children : null}</div>
  </details>;
}

import type { ChatAttachment } from '../../../../types/domain';
import { useAssetObjectUrl } from '../../../useAssetObjectUrl';
import { useNearViewportAssetLoading } from '../ChatAttachmentStrip';

export function MessageSticker({ attachment, pending = false, onRemove }: {
  attachment: ChatAttachment; pending?: boolean; onRemove?: (id: string) => void;
}) {
  const { ref, shouldLoad } = useNearViewportAssetLoading();
  const url = useAssetObjectUrl(shouldLoad && !attachment.clearedAt ? attachment.assetId : undefined, true);
  const name = attachment.name.replace(/^表情包-/, '').replace(/\.[a-z0-9]+$/i, '') || '表情包';
  return <div ref={ref} className={`message-sticker${pending ? ' pending' : ''}`}>
    {url ? <img src={url} alt={name} draggable={false} loading="lazy" /> : <span className="message-sticker-placeholder" role="img" aria-label={attachment.clearedAt ? '表情包已清理' : `正在加载${name}`}>{attachment.clearedAt ? '表情包已清理' : ''}</span>}
    {onRemove && <button type="button" className="attachment-remove-btn" onClick={() => onRemove(attachment.id)} aria-label={`移除表情包：${name}`}>×</button>}
  </div>;
}

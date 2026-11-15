import type { ChatAttachment, ChatMessage } from '../../../../types/domain';

export function isStickerAttachment(attachment: ChatAttachment) {
  return attachment.kind === 'image' && (/^表情包-/.test(attachment.name) || attachment.textContent?.startsWith('表情包：') === true);
}
export function isStickerToolMessage(message: ChatMessage) {
  const name = message.toolInvocation?.mcpResult?.toolName ?? message.toolInvocation?.toolName;
  return name === 'send_azhou_sticker_image' || name === 'send_azhou_sticker';
}
export function isQuietMcpSuccess(message: ChatMessage) {
  const tool = message.toolInvocation;
  return tool?.kind === 'invokeMcpTool' && tool.status === 'executed' && tool.mcpResult?.isError !== true;
}
/** Presentation only. Structured Markdown and the single stored message stay intact. */
export function splitAssistantChatProse(content: string) {
  if (/```|~~~|^\s*(?:#{1,6}\s|>\s|[-*+]\s|\d+[.)]\s)|\|.*\||\$\$|<\/?[a-z]/im.test(content)) return [content];
  const pieces = content.trim().split(/\n[\t ]*\n+/).filter(Boolean);
  return pieces.length > 1 && pieces.length <= 32 ? pieces : [content];
}

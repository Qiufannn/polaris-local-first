import type { ChatActivityDisplay, ChatMessage } from '../../../../types/domain';
import { TOOL_DRAFT_BLOCK_PATTERN } from '../../../../app/chat/chatMarkdownPatterns';
import { stripCodeBlocksFromMessage } from '../../../../engines/codeCardEngine';
import type { TimelineRenderItem } from './messageTimelineItems';

export function activityMessages(item: TimelineRenderItem): ChatMessage[] {
  return item.message.toolInvocation ? [item.message] : item.toolMessages;
}

export function needsActivityAttention(message: ChatMessage) {
  return message.toolInvocation?.status === 'failed' || message.toolInvocation?.status === 'preview';
}

function isActivityOnly(item: TimelineRenderItem) {
  const { message } = item;
  const tools = activityMessages(item);
  if (!tools.length || message.cardReference || message.attachments?.length
    || tools.some(tool => tool.attachments?.length || tool.toolInvocation?.cardId)) return false;
  if (message.toolInvocation) return true;
  if (message.role !== 'assistant') return false;
  let content = message.content.replace(TOOL_DRAFT_BLOCK_PATTERN, '').trim();
  // Code associated with execution is activity; ordinary code answers remain visible.
  if (tools.some(tool => tool.toolInvocation?.kind === 'runCode')) content = stripCodeBlocksFromMessage(content).trim();
  return !content;
}

/** A view projection only: never change stored messages or model-visible tool results. */
export function projectTimelineActivity(items: TimelineRenderItem[], mode: ChatActivityDisplay): TimelineRenderItem[] {
  if (mode === 'full') return items;
  const result: TimelineRenderItem[] = [];
  let group: TimelineRenderItem[] = [];
  const flush = () => {
    if (!group.length) return;
    result.push({ ...group[0], message: { ...group[0].message, id: `activity:${group[0].message.id}` }, activityItems: group });
    group = [];
  };
  for (const item of items) {
    if (!isActivityOnly(item)) { flush(); result.push(item); continue; }
    if (mode === 'hidden') {
      flush();
      if (activityMessages(item).some(needsActivityAttention)) result.push(item);
      continue;
    }
    if (item.message.role === 'assistant' && !item.isAssistantContinuation) flush();
    group.push(item);
  }
  flush();
  let hasAssistant = false;
  return result.map(item => {
    if (item.message.role === 'user') hasAssistant = false;
    if (item.activityItems || item.message.role !== 'assistant' || item.message.toolInvocation || isActivityOnly(item)) return item;
    const next = { ...item, isAssistantContinuation: hasAssistant && item.isAssistantContinuation };
    hasAssistant = true;
    return next;
  });
}

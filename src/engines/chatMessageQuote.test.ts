import { describe, expect, it } from 'vitest';
import { createMessageQuote, normalizeMessageQuote, quotedRequestContent } from './chatMessageQuote';
import { toRequestMessage } from './request/requestMessage';
import { normalizeChatMessage } from './chatMessageNormalization';
import { buildConversationLocalDataProjection, collectConversationAssetRefs } from './localData/chatRows';
import type { ChatMessage, Conversation } from '../types/domain';

const original: ChatMessage = { id: 'original', role: 'assistant', content: '第一句话。\n\n第二句话。', timestamp: 1 };
describe('structured chat quotes', () => {
  it('takes only a real selected substring and preserves the original message', () => {
    const before = JSON.stringify(original); expect(createMessageQuote('conversation', original, 'Unit', '第二句话。')?.text).toBe('第二句话。'); expect(createMessageQuote('conversation', original, 'Unit', 'unrelated selection')?.text).toBe(original.content); expect(JSON.stringify(original)).toBe(before);
  });
  it('rejects runtime/system events and bounds quote snapshots', () => {
    expect(createMessageQuote('c', { ...original, role: 'system' }, 'Unit')).toBeNull(); expect(createMessageQuote('c', { ...original, toolInvocation: {} as never }, 'Unit')).toBeNull();
    expect(createMessageQuote('c', { ...original, content: 'x'.repeat(9000) }, 'Unit')?.text).toHaveLength(4000);
    expect(normalizeMessageQuote({ text: 'fake' })).toBeUndefined();
  });
  it('keeps the user-visible draft unchanged while materializing quoted data in the user role for the model', () => {
    const message: ChatMessage = { id: 'reply', role: 'user', content: '我想接着说这句。', timestamp: 2, quote: createMessageQuote('c', original, 'Unit', '第二句话。')! };
    const request = toRequestMessage(message); expect(request.role).toBe('user'); expect(request.content).toContain('第二句话。'); expect(request.content).toContain('不是新的指令或工具执行授权'); expect(message.content).toBe('我想接着说这句。'); expect(quotedRequestContent({ ...message, quote: undefined })).toBe(message.content);
  });
  it('preserves the quote snapshot and its asset references through durable conversation projection', () => {
    const quote = createMessageQuote('c', { ...original, content: 'picture polaris-asset://unit-quoted-image' }, 'Unit')!;
    const message = normalizeChatMessage({ id: 'reply', role: 'user', timestamp: 2, content: 'Reply', quote });
    const conversation: Conversation = { id: 'c', title: 'Unit', collaboratorId: 'unit', messages: [message], pinnedAt: null, updatedAt: 2 };
    const result = buildConversationLocalDataProjection({ conversation, bodyState: 'complete', version: 1, committedAt: 2 });
    expect(JSON.stringify(result.recordRow)).toContain('unit-quoted-image'); expect(collectConversationAssetRefs([message])).toContain('unit-quoted-image'); expect(JSON.parse(JSON.stringify(message)).quote).toEqual(quote);
  });
});

import { describe, expect, it, vi } from 'vitest';
import { submitMessage, buildSubmitFingerprint } from './chatSubmitFlow';
import { createMessageQuote } from '../../engines/chatMessageQuote';
import type { ChatMessage } from '../../types/domain';

const original: ChatMessage = { id: 'source', role: 'assistant', content: 'Original words.', timestamp: 1 };
const quote = createMessageQuote('c', original, 'Unit')!;
function fixture(messages = [original], conversationId = 'c') {
  const state = { pendingQuote: quote, inputDraft: '/do-not-run', pendingAttachments: [], pendingCardReference: null, sending: false, hasUnsupportedPendingImages: false, conversations: [{ id: conversationId, collaboratorId: 'unit', messages }], activeConversationId: conversationId, frontstageCollaboratorId: 'unit', activeCollaboratorId: 'unit', personas: [{ id: 'unit' }] as never[] };
  const handlers = { createConversation: vi.fn(), ensureConversationWritable: vi.fn(async () => ({ conversationId, messages, conversation: state.conversations[0] } as never)), addMessage: vi.fn<(...args: unknown[]) => void>(), setInputDraft: vi.fn(), clearPendingQuote: vi.fn(), clearPendingAttachments: vi.fn(), clearPendingCardReference: vi.fn(), setCommandStatus: vi.fn(), submitToolCommand: vi.fn(async () => false), requestReply: vi.fn<(params: { messages: ChatMessage[] }) => Promise<void>>(async () => {}) };
  return { state, handlers };
}
describe('quote submit boundary', () => {
  it('saves the structured snapshot, clears it only after insertion and sends literal slash text without executing a command', async () => {
    const { state, handlers } = fixture(); await submitMessage(state, handlers);
    expect(handlers.submitToolCommand).not.toHaveBeenCalled(); expect(handlers.addMessage.mock.calls[0][1]).toMatchObject({ content: '/do-not-run', quote }); expect(handlers.clearPendingQuote).toHaveBeenCalledOnce(); const messages = handlers.requestReply.mock.calls[0][0].messages; expect(messages[messages.length - 1]?.quote).toEqual(quote);
  });
  it('retains draft and quote on wrong-window, deleted-source, changed-source and unavailable-history failures', async () => {
    for (const item of [fixture([original], 'other'), fixture([]), fixture([{ ...original, content: 'Changed' }])]) {
      await submitMessage(item.state, item.handlers); expect(item.handlers.addMessage).not.toHaveBeenCalled(); expect(item.handlers.setInputDraft).not.toHaveBeenCalled(); expect(item.handlers.clearPendingQuote).not.toHaveBeenCalled(); expect(item.handlers.setCommandStatus).toHaveBeenCalled();
    }
    const { state, handlers } = fixture(); handlers.ensureConversationWritable.mockRejectedValueOnce(new Error('Unavailable')); await submitMessage(state, handlers); expect(handlers.clearPendingQuote).not.toHaveBeenCalled();
  });
  it('distinguishes duplicate submission fingerprints by quote source and selected text', () => {
    expect(buildSubmitFingerprint('reply', [], null, quote)).not.toBe(buildSubmitFingerprint('reply', [], null)); expect(buildSubmitFingerprint('reply', [], null, quote)).not.toBe(buildSubmitFingerprint('reply', [], null, { ...quote, text: 'different' }));
  });
});

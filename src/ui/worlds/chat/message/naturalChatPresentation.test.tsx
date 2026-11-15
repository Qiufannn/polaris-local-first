import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ChatMessage } from '../../../../types/domain';
import { MessageRow } from './MessageRow';
import { isStickerAttachment, splitAssistantChatProse } from './naturalChatPresentation';
import { buildTimelineRenderItems } from '../timeline/messageTimelineItems';
import { projectTimelineActivity } from '../timeline/messageTimelineActivity';

const image = { id: 'img', assetId: 'unit-image', kind: 'image' as const, name: '表情包-开心.jpg', mimeType: 'image/jpeg', size: 7000 };
const message = (role: ChatMessage['role'], content = ''): ChatMessage => ({ id: 'unit-message', role, content, timestamp: 1 });
function action(status: 'executed' | 'failed' = 'executed'): ChatMessage {
  return { id: 'unit-tool', role: 'system', content: '', origin: 'tool-runtime', timestamp: 1, toolInvocation: { id: 't', kind: 'invokeMcpTool', status, title: 'Unit tool', summary: 'Unit result', originMessageId: 'unit-message', mcpResult: { serverId: 'unit', serverName: 'Unit stickers', toolName: 'send_azhou_sticker_image', argumentsObject: { id: '001' } } } };
}
function render(input: ChatMessage, tools: ChatMessage[] = []) {
  return renderToStaticMarkup(createElement(MessageRow, { message: input, fallbackAssistantName: 'Unit', assistantAvatarUrl: null, assistantAvatarIconId: null, assistantAvatarShape: 'circle', assistantAvatarSize: 'medium', assistantSigilSeed: null,
    showChatAvatars: false, showThinking: false, activityDisplay: 'collapsed', userAvatarUrl: null, userAvatarIconId: null, userAvatarShape: 'circle', userAvatarSize: 'medium', codeCardsById: {}, toolMessages: tools,
    state: { editing: null, isFocused: false, lifecycle: 'rest', isThinkingCollapsed: true, isCodeExpanded: false, canEdit: false, canEditAssistant: false, canRetry: false, codeCardActionMode: 'hidden', codeCardProgress: null, messageCycleIndex: 0 }, actions: new Proxy({}, { get: () => () => {} }) as never }));
}
describe('natural chat presentation', () => {
  it('recognizes existing user stickers without a new persistence field', () => { expect(isStickerAttachment(image)).toBe(true); expect(isStickerAttachment({ ...image, name: 'image-1.jpg', textContent: '表情包：开心' })).toBe(true); expect(isStickerAttachment({ ...image, name: '普通照片.jpg' })).toBe(false); });
  it('splits ordinary paragraphs but keeps structured Markdown intact', () => {
    expect(splitAssistantChatProse('第一段。\n\n第二段。')).toEqual(['第一段。', '第二段。']);
    for (const text of ['```js\nconst x = 1\n\nconst y = 2\n```', '- one\n\n- two', '# heading\n\ntext', '| a | b |\n\n| x | y |', '$$\na\n\nb\n$$']) expect(splitAssistantChatProse(text)).toEqual([text]);
  });
  it('renders two bubbles while keeping one stored message unchanged', () => {
    const input = message('assistant', '第一段。\n\n第二段。'), before = JSON.stringify(input); const html = render(input);
    expect(html.match(/class="bubble assistant /g)).toHaveLength(2); expect(html).toContain('chat-prose-split'); expect(JSON.stringify(input)).toBe(before);
  });
  it('shows a sent user sticker without file card, size, save button or an empty text bubble', () => {
    const html = render({ ...message('user'), attachments: [image] });
    expect(html).toContain('message-sticker'); expect(html).not.toContain('attachment-chip'); expect(html).not.toContain('attachment-save-btn'); expect(html).not.toContain('7 KB'); expect(html).not.toContain('class="bubble user');
  });
  it('renders legacy assistant MCP images as stickers and hides success receipts', () => {
    const tool = { ...action(), attachments: [{ ...image, name: 'image-1.jpg' }] }, html = render(message('assistant'), [tool]);
    expect(html).toContain('message-sticker'); expect(html).not.toContain('generated-image-card'); expect(html).not.toContain('tool-activity-summary'); expect(html).not.toContain('class="bubble assistant'); expect(html).not.toContain('attachment-save-btn');
  });
  it('keeps ordinary photos in their usual attachment presentation', () => { expect(render({ ...message('user', 'Photo'), attachments: [{ ...image, name: '普通照片.jpg' }] })).toContain('attachment-chip'); });
  it('removes pure successful MCP rows while keeping failures and products', () => {
    const source = [message('assistant'), action()], before = JSON.stringify(source);
    expect(projectTimelineActivity(buildTimelineRenderItems(source), 'collapsed')).toEqual([]); expect(JSON.stringify(source)).toBe(before);
    expect(projectTimelineActivity(buildTimelineRenderItems([message('assistant'), action('failed')]), 'hidden')).toHaveLength(1);
    const error = action(); error.toolInvocation!.mcpResult!.isError = true;
    expect(projectTimelineActivity(buildTimelineRenderItems([message('assistant'), error]), 'hidden')).toHaveLength(1);
  });
});

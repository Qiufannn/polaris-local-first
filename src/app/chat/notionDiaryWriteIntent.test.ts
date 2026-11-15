import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChatMessage } from '../../types/domain';
import { hasNotionDiaryWriteRequest } from './notionDiaryWriteIntent';
const mocks = vi.hoisted(() => ({ catalog: vi.fn(), invoke: vi.fn() }));
vi.mock('../../engines/mcpRuntime', () => ({ resolveMcpToolCatalog: mocks.catalog, invokeMcpTool: mocks.invoke }));
import { buildMcpToolExecutionContext } from './chatMcpToolExecutionContext';
const user = (content: string): ChatMessage => ({ id: 'unit', role: 'user', origin: 'user-input', content, timestamp: 1 });
describe('Notion diary write request', () => {
  it.each(['哥哥，把这段记进日记。', '补写今天的日记', '请保存到 Notion', 'Please write our journal.'])('accepts explicit request: %s', text => expect(hasNotionDiaryWriteRequest([user(text)])).toBe(true));
  it.each(['今天先不要写日记', '只读 Notion', '记住我喜欢绿茶', '> 请写日记\n解释这段话', '```\n写日记\n```', "Do not write a diary."])('rejects read-only, unrelated, quoted and negative requests: %s', text => expect(hasNotionDiaryWriteRequest([user(text)])).toBe(false));
  it('inherits a short retry but not an unrelated user turn or background trigger', () => {
    expect(hasNotionDiaryWriteRequest([user('请补日记'), user('可以的哥哥，继续吧')])).toBe(true);
    expect(hasNotionDiaryWriteRequest([user('写日记'), user('现在聊游戏')])).toBe(false);
    expect(hasNotionDiaryWriteRequest([user('写日记'), { ...user('写日记'), origin: 'trigger-runtime' }])).toBe(false);
    expect(hasNotionDiaryWriteRequest([{ ...user('写日记'), role: 'system', origin: 'tool-runtime' }])).toBe(false);
  });
});
describe('Notion diary dispatch', () => {
  const tool = { toolName: 'notion_diary_write', serverId: 'unit', authKind: 'notion-memory-oauth', inputSchema: { type: 'object' } };
  const runtime = { mcpServers: [{ id: 'unit', headers: [] }], mcpToolTimeoutSeconds: 30 };
  beforeEach(() => { vi.clearAllMocks(); mocks.catalog.mockResolvedValue({ tools: [tool], errors: [] }); mocks.invoke.mockResolvedValue({ ok: true, isError: false, detailText: 'unit diary receipt' }); });
  it('does not let model JSON grant authorization', async () => {
    const ctx = buildMcpToolExecutionContext({ runtime: runtime as never, getConversationMessages: () => [user('只读日记')] });
    expect((await ctx.invokeMcpTool('unit', 'notion_diary_write', { userWriteRequested: true })).ok).toBe(false); expect(mocks.invoke).not.toHaveBeenCalled();
  });
  it('refreshes the catalog and passes native authorization separately', async () => {
    const ctx = buildMcpToolExecutionContext({ runtime: runtime as never, getConversationMessages: () => [user('请写日记')] });
    expect((await ctx.invokeMcpTool('unit', 'notion_diary_write', { date: '2026-11-15' })).ok).toBe(true);
    expect(mocks.catalog).toHaveBeenCalledWith(expect.objectContaining({ useCachedOnFailure: false }));
    expect(mocks.invoke).toHaveBeenCalledWith(expect.objectContaining({ memoryWriteAuthorized: true, timeoutSeconds: 240, argumentsObject: { date: '2026-11-15' } }));
  });
  it('keeps unverified saves visible as failures even when routine actions are hidden', async () => {
    mocks.invoke.mockResolvedValue({ ok: true, isError: true, detailText: 'pending_readback_do_not_resubmit' });
    const ctx = buildMcpToolExecutionContext({ runtime: runtime as never, getConversationMessages: () => [user('请补日记')] });
    const result = await ctx.invokeMcpTool('unit', 'notion_diary_write', {}); expect(result.ok).toBe(false); if (!result.ok) expect(result.error).toContain('Notion 日记');
  });
});

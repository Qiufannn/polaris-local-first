import { describe, expect, it } from 'vitest';
import type { Conversation } from '../types/domain';
import { selectMemoryRecallConversations } from './memoryConversationScope';
import { searchCollaboratorMemorySources } from './memoryToolSearch';
import { selectRequestSemanticRecallConversationIds } from '../app/chat/chatSemanticRecallCorpus';
import { resolveRequestSemanticRecallPlan, resolveSemanticRecallContextCandidates } from './request/requestSemanticRecallPlan';

function group(id = 'pets', members = ['azhou', 'cat', 'dog']): Conversation {
  return {
    id, title: '一起养小猫小狗', kind: 'group', collaboratorId: null, pinnedAt: null, updatedAt: 5,
    group: {
      title: '一起养小猫小狗', memberIds: members, background: '', replyMode: 'round',
      allowMemberSilence: true, memoryRecallEnabled: true,
      toolSettings: { cards: false, images: false, attachments: false, web: false, mcp: false },
      createdAt: 1, updatedAt: 5,
      privateLanes: { cat: [{ id: 'secret', kind: 'user-note', author: 'user', content: '猫咪私域秘密', createdAt: 4 }] }
    },
    messages: [
      { id: 'u', role: 'user', content: '我们把小猫叫团团，小狗叫圆圆，一起玩毛线球。', timestamp: 1 },
      { id: 'a', role: 'assistant', assistantName: '阿昼', speakerCollaboratorId: 'azhou', content: '团团，圆圆，来一起玩毛线球。', timestamp: 2 },
      { id: 'c', role: 'assistant', assistantName: '团团', speakerCollaboratorId: 'cat', content: '喵，我要蓝色毛线球。', timestamp: 3 },
      { id: 'd', role: 'assistant', assistantName: '圆圆', speakerCollaboratorId: 'dog', content: '汪，我来接球！', timestamp: 4 }
    ]
  };
}

describe('direct/group memory continuity', () => {
  it('finds the member group despite its null owner, while keeping other private chats and groups out', () => {
    const shared = group();
    const excluded = [
      group('unrelated', ['cat', 'dog']),
      { ...group('off'), group: { ...shared.group!, memoryRecallEnabled: false } },
      { ...group('stale-owner', ['cat']), collaboratorId: 'azhou' },
      { ...shared, id: 'cat-private', kind: 'direct' as const, group: undefined, collaboratorId: 'cat' },
      { ...shared, id: 'retired', groupRoomId: 'legacy-room' }
    ];
    const ids = selectRequestSemanticRecallConversationIds({
      conversations: [shared, ...excluded], activeConversationId: 'azhou-private', currentCollaboratorId: 'azhou'
    });
    expect(ids).toEqual(['pets']);
    expect(selectMemoryRecallConversations([shared, ...excluded], 'azhou').map((c) => c.id)).toEqual(['pets']);
  });

  it('recalls a compact group turn into a direct chat with distinct speakers and no private lane', () => {
    const conversations = [group()];
    const plan = resolveRequestSemanticRecallPlan({
      conversations, activeConversationId: 'azhou-private', currentCollaboratorId: 'azhou',
      messages: [{ id: 'now', role: 'user', content: '哥哥，刚才在群里小猫小狗玩什么？', timestamp: 10 }],
      maxTokens: null, maxCandidates: 3
    });
    const candidates = resolveSemanticRecallContextCandidates({ plan, conversations });
    const text = candidates.map((candidate) => candidate.text).join('\n');
    expect(plan.selectedCandidates.length).toBeLessThanOrEqual(3);
    expect(candidates.some((candidate) => candidate.label === '群聊｜一起养小猫小狗')).toBe(true);
    expect(text).toContain('assistant (阿昼):');
    expect(text).toContain('assistant (团团):');
    expect(text).toContain('assistant (圆圆):');
    expect(text).not.toContain('猫咪私域秘密');
    expect(plan.selectedCandidates[0].sourceMessageIds).toEqual(['u', 'a', 'c', 'd']);
  });

  it('lets the same member recall their direct chat from a group without inheriting another pet private chat', () => {
    const direct = { ...group(), id: 'azhou-private', kind: 'direct' as const, group: undefined, collaboratorId: 'azhou' };
    const catPrivate = { ...direct, id: 'cat-private', collaboratorId: 'cat' };
    const plan = resolveRequestSemanticRecallPlan({
      conversations: [direct, catPrivate, group()], activeConversationId: 'pets', currentCollaboratorId: 'azhou',
      messages: [{ id: 'now', role: 'user', content: '记得毛线球吗', timestamp: 10 }], maxTokens: null
    });
    expect(plan.selectedCandidates.map((candidate) => candidate.sourceConversationId)).toEqual(['azhou-private']);
    const petPlan = resolveRequestSemanticRecallPlan({
      conversations: [direct], activeConversationId: 'pets', currentCollaboratorId: 'cat',
      messages: [], maxTokens: null
    });
    expect(petPlan.selectedCandidates).toEqual([]);
  });

  it('explicit memory search uses the same group membership and recall-off boundaries', () => {
    const shared = group();
    const result = searchCollaboratorMemorySources({
      query: '蓝色毛线球', mode: 'source', currentCollaboratorId: 'azhou', activeConversationId: 'azhou-private',
      conversations: [shared, group('unrelated', ['cat']), { ...group('off'), group: { ...shared.group!, memoryRecallEnabled: false } }]
    });
    expect(result.sources.map((source) => source.conversationId)).toEqual(['pets']);
    expect(result.sources[0].text).toContain('assistant (团团): 喵，我要蓝色毛线球。');
    expect(result.sources[0].text).not.toContain('猫咪私域秘密');
  });
});

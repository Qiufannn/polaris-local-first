import { describe, expect, it, vi } from 'vitest';
import { createChatMemoryActions } from './chatMemoryToolActions';
import type { ChatMessage, Conversation, Persona, ToolInvocation } from '../../types/domain';
import type { WritableConversationBody } from '../../stores/chatStore';

function writableConversation(): WritableConversationBody {
  return {
    conversationId: 'conv-1',
    conversation: {
      id: 'conv-1',
      title: '测试对话',
      collaboratorId: 'pharos',
      draft: '',
      pinnedAt: null,
      updatedAt: 1,
      messages: []
    },
    messages: []
  };
}

function createPersona(): Persona {
  return {
    id: 'pharos',
    name: 'Pharos',
    avatar: '',
    role: 'assistant',
    prompt: '',
    tone: '',
    accent: '',
    tags: {
      temperament: [],
      interaction: [],
      expression: [],
      thinking: [],
      action: []
    },
    color: '#000',
    createdAt: 1,
    updatedAt: 1,
    memory: {
      inheritGlobal: true,
      excludedGlobalIds: [],
      personalMemories: [],
      referenceDocs: []
    },
    deepDefinition: {
      identityHint: '',
      missionHint: '',
      conflictPriority: '',
      conflictReason: '',
      avoidBecoming: '',
      correctiveAction: '',
      vulnerableFirst: '',
      vulnerableThen: '',
      hardBoundary: '',
      hardBoundaryAction: ''
    },
    advanced: {
      modelOverride: '',
      temperature: '',
      topP: '',
      maxTokens: '',
      thinkingBudget: '',
      contextMessageLimit: '',
      showThinking: false,
      streaming: true,
      customHeaders: '',
      customBody: '',
      regexRules: '',
      snippets: []
    }
  } as unknown as Persona;
}

function createMemoryActions(conversations: Conversation[] = []) {
  const persona = createPersona();
  const addRuntimeToolMessage = vi.fn();
  const setCommandStatus = vi.fn();
  const updateCollaborator = vi.fn();
  const updateMessage = vi.fn();
  const actions = createChatMemoryActions({
    ui: { setCommandStatus },
    store: {
      chat: {
        conversations: [],
        getMemoryRecallConversations: () => conversations,
        findConversation: vi.fn(() => ({ id: 'conv-1', collaboratorId: 'pharos' })),
        updateMessage
      } as never,
      persona: {
        activeCollaboratorId: 'pharos',
        findCollaborator: vi.fn((id: string) => id === 'pharos' ? persona : null),
        updateCollaborator
      } as never
    },
    frontstageCollaboratorId: null,
    activeConversation: { id: 'conv-1', collaboratorId: 'pharos' },
    addRuntimeToolMessage
  });

  return {
    actions,
    addRuntimeToolMessage,
    setCommandStatus,
    updateMessage,
    updateCollaborator
  };
}

function memoryPreviewMessage(): ChatMessage {
  return {
    id: 'memory-preview-message',
    role: 'system',
    content: '确认写入记忆',
    timestamp: 1,
    toolInvocation: {
      id: 'tool-memory-preview',
      kind: 'writeMemory',
      status: 'preview',
      title: '确认写入记忆',
      summary: '确认写入',
      memoryItems: ['她的手机号是 123456']
    }
  };
}

describe('createChatMemoryActions', () => {
  it('searches and opens the member group public messages through the recall catalog, preserving pet names', () => {
    const shared = {
      id: 'pets', title: '小猫小狗', kind: 'group', collaboratorId: null, updatedAt: 5,
      group: {
        memberIds: ['pharos', 'cat'], memoryRecallEnabled: true,
        privateLanes: { cat: [{ content: '私域内容不公开' }] }
      },
      messages: [
        { id: 'u', role: 'user', content: '给小猫一个蓝色毛线球', timestamp: 1 },
        { id: 'c', role: 'assistant', assistantName: '团团', speakerCollaboratorId: 'cat', content: '蓝色毛线球真好玩', timestamp: 2 }
      ]
    } as unknown as Conversation;
    const { actions } = createMemoryActions([shared]);
    const searched = actions.searchCollaboratorMemory!('蓝色毛线球', 'source', 3, 'conv-1');
    expect(searched.ok).toBe(true);
    if (searched.ok) expect(searched.detailText).toContain('sourceConversationId=pets');
    const opened = actions.openMemorySource!('pets', ['c'], 1000, 'conv-1');
    expect(opened.ok).toBe(true);
    if (opened.ok) {
      expect(opened.detailText).toContain('assistant (团团)');
      expect(opened.detailText).toContain('蓝色毛线球真好玩');
      expect(opened.detailText).not.toContain('私域内容不公开');
    }
    shared.group!.memberIds = ['cat'];
    expect(actions.openMemorySource!('pets', ['c'], 1000, 'conv-1').ok).toBe(false);
    shared.group!.memberIds = ['pharos', 'cat'];
    shared.group!.memoryRecallEnabled = false;
    expect(actions.openMemorySource!('pets', ['c'], 1000, 'conv-1').ok).toBe(false);
  });

  it('writes low-risk memory items immediately', () => {
    const { actions, addRuntimeToolMessage } = createMemoryActions();

    const handled = actions.maybeHandleWriteMemoryAction(writableConversation(), {
      kind: 'writeMemory',
      memory: ['喜欢清楚边界']
    });

    expect(handled).toBe(true);
    const invocation = addRuntimeToolMessage.mock.calls[0]?.[1] as ToolInvocation | undefined;
    expect(invocation?.status).toBe('executed');
    expect(invocation?.summary).toContain('已写入 1 条低风险记忆');
  });

  it('does not show removed legacy copy for low-risk writes', () => {
    const { actions, addRuntimeToolMessage, setCommandStatus } = createMemoryActions();

    const handled = actions.maybeHandleWriteMemoryAction(writableConversation(), {
      kind: 'writeMemory',
      memory: ['喜欢清楚边界']
    });

    expect(handled).toBe(true);
    const invocation = addRuntimeToolMessage.mock.calls[0]?.[1] as ToolInvocation | undefined;
    expect(invocation?.status).toBe('executed');
    expect(invocation?.summary).not.toContain('关闭了自动写入');
    expect(setCommandStatus).not.toHaveBeenCalled();
  });

  it('previews sensitive memory writes without removed legacy copy', () => {
    const { actions, addRuntimeToolMessage } = createMemoryActions();

    const handled = actions.maybeHandleWriteMemoryAction(writableConversation(), {
      kind: 'writeMemory',
      memory: ['她的手机号是 123456']
    });

    expect(handled).toBe(true);
    const invocation = addRuntimeToolMessage.mock.calls[0]?.[1] as ToolInvocation | undefined;
    expect(invocation?.status).toBe('preview');
    expect(invocation?.summary).toContain('可能偏敏感');
    expect(invocation?.summary).not.toContain('关闭了自动写入');
  });

  it('applies memory previews through the provided writable conversation', () => {
    const target = writableConversation();
    const { actions, updateMessage } = createMemoryActions();
    const message = memoryPreviewMessage();

    expect(actions.applyMemoryPreview(target, message)).toBe(true);

    expect(updateMessage).toHaveBeenCalledWith(
      target,
      'memory-preview-message',
      expect.objectContaining({
        content: '已写入当前协作者记忆。',
        toolInvocation: expect.objectContaining({ status: 'applied' })
      })
    );
  });

  it('rolls back memory previews through the provided writable conversation', () => {
    const target = writableConversation();
    const { actions, updateMessage } = createMemoryActions();
    const message = memoryPreviewMessage();

    expect(actions.rollbackMemoryPreview(target, message)).toBe(true);

    expect(updateMessage).toHaveBeenCalledWith(
      target,
      'memory-preview-message',
      expect.objectContaining({
        content: '这批记忆没有写入。',
        toolInvocation: expect.objectContaining({ status: 'rolled_back' })
      })
    );
  });
});

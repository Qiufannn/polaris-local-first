import { buildWeightedMemoryLines, classifyMemoryWriteItems, memoryPreviewSummary } from '../../engines/memoryEngine';
import { createUid } from '../../engines/id';
import { memorySourceRoleLabel, selectMemoryRecallConversations } from '../../engines/memoryConversationScope';
import { isNaturalMemorySourceMessage } from '../../engines/memoryNaturalSourceMessage';
import { readConversationMessages } from '../../stores/chatCurrentPersistence';
import { orderMemoryReferenceDocsNewestFirst } from '../../engines/memoryReferenceDocs';
import {
  openConversationMemorySource,
  searchCollaboratorMemorySources,
  type MemorySearchMode
} from '../../engines/memoryToolSearch';
import { readPersonaMemoryDocContent, stagePersonaMemoryDocContent } from '../../stores/personaMemoryReferenceDocPersistence';
import type { ToolAction } from '../../engines/toolExecutor';
import type { WritableConversationBody } from '../../stores/chatStore';
import type { ChatMessage, PersonaMemoryReferenceDoc } from '../../types/domain';
import { resolveChatCollaboratorOwnerId } from './chatCollaboratorOwner';
import type { ActiveConversationCollaborator } from './chatConversationCollaborator';
import type { AddRuntimeToolMessage, ChatToolStoreBindings, ChatUiToolState, MemoryActions } from './chatPorts';

type CreateChatMemoryActionsArgs = {
  ui: Pick<ChatUiToolState, 'setCommandStatus'>;
  store: Pick<ChatToolStoreBindings, 'chat' | 'persona'>;
  frontstageCollaboratorId: string | null;
  activeConversation: ActiveConversationCollaborator | null;
  addRuntimeToolMessage: AddRuntimeToolMessage;
};

function formatDate(timestamp: number) {
  return Number.isFinite(timestamp) && timestamp > 0
    ? new Date(timestamp).toISOString().slice(0, 10)
    : 'unknown date';
}

function excerptText(text: string, maxChars = 520) {
  const normalized = text.trim().replace(/\s+/g, ' ');
  return normalized.length > maxChars ? `${normalized.slice(0, maxChars)}...` : normalized;
}

export function createChatMemoryActions({
  ui,
  store,
  frontstageCollaboratorId,
  activeConversation,
  addRuntimeToolMessage
}: CreateChatMemoryActionsArgs): MemoryActions {
  const recallConversations = (collaboratorId: string) => selectMemoryRecallConversations(
    store.chat.getMemoryRecallConversations?.() ?? store.chat.conversations ?? [],
    collaboratorId
  );
  const resolveTargetCollaborator = (conversationId?: string | null) => {
    const targetCollaboratorId = resolveChatCollaboratorOwnerId({
      frontstageCollaboratorId,
      activeConversationCollaboratorId: activeConversation?.collaboratorId,
      conversationCollaboratorId: conversationId
        ? store.chat.findConversation(conversationId)?.collaboratorId
        : undefined,
      fallbackCollaboratorId: store.persona.activeCollaboratorId
    });

    return (targetCollaboratorId ? store.persona.findCollaborator(targetCollaboratorId) : null) ?? null;
  };

  const appendCollaboratorMemories = (items: string[], conversationId?: string | null) => {
    const targetCollaborator = resolveTargetCollaborator(conversationId);
    if (!targetCollaborator) return false;

    const nextMemories = buildWeightedMemoryLines([
      ...targetCollaborator.memory.personalMemories,
      ...items
    ]);

    store.persona.updateCollaborator(targetCollaborator.id, {
      memory: {
        ...targetCollaborator.memory,
        personalMemories: nextMemories
      }
    });
    return true;
  };

  const writeCollaboratorMemoryDoc = (
    doc: Pick<PersonaMemoryReferenceDoc, 'title' | 'content'> & { docId?: string; summary?: string },
    conversationId?: string | null
  ) => {
    const targetCollaborator = resolveTargetCollaborator(conversationId);
    if (!targetCollaborator) return { ok: false as const, error: '当前没有可写入长期资料的协作者。' };

    const title = doc.title.trim();
    const content = doc.content.trim();
    if (!title) return { ok: false as const, error: '没有可写入的长期资料标题。' };
    if (!content) return { ok: false as const, error: '没有可写入的长期资料正文。' };

    const summary = doc.summary?.trim() ?? '';
    const normalizedDocId = doc.docId?.trim();
    const normalizedTitle = title.toLowerCase();
    const currentDocs = targetCollaborator.memory.referenceDocs ?? [];
    const existingIndex = currentDocs.findIndex((item) => (
      (normalizedDocId ? item.id === normalizedDocId : false)
      || item.title.trim().toLowerCase() === normalizedTitle
    ));
    const now = Date.now();
    const docId = existingIndex >= 0 ? currentDocs[existingIndex].id : (normalizedDocId || createUid('memory-doc'));
    const nextDoc: PersonaMemoryReferenceDoc = {
      id: docId,
      title,
      summary,
      content,
      charCount: content.length,
      contentLoaded: true,
      source: existingIndex >= 0 ? currentDocs[existingIndex].source : 'collaborator',
      updatedAt: now
    };
    const nextDocs = existingIndex >= 0
      ? orderMemoryReferenceDocsNewestFirst(currentDocs.map((item, index) => index === existingIndex ? nextDoc : item))
      : orderMemoryReferenceDocsNewestFirst([nextDoc, ...currentDocs]);

    stagePersonaMemoryDocContent(targetCollaborator.id, docId, content);
    store.persona.updateCollaborator(targetCollaborator.id, {
      memory: {
        ...targetCollaborator.memory,
        referenceDocs: nextDocs
      }
    });

    return {
      ok: true as const,
      docId,
      title,
      created: existingIndex < 0
    };
  };

  const readCollaboratorMemoryDoc = async (docId: string, conversationId?: string | null) => {
    const targetCollaborator = resolveTargetCollaborator(conversationId);
    if (!targetCollaborator) return null;

    const normalizedDocId = docId.trim();
    const normalizedTitle = normalizedDocId.toLowerCase();
    const doc = (targetCollaborator.memory.referenceDocs ?? []).find((entry) => (
      entry.id === normalizedDocId
      || entry.title.trim().toLowerCase() === normalizedTitle
    )) ?? null;
    if (!doc) return null;
    const content = await readPersonaMemoryDocContent(targetCollaborator.id, doc);
    return {
      ...doc,
      content,
      charCount: content.length,
      contentLoaded: true
    };
  };

  const listCollaboratorMemoryDocs = (conversationId?: string | null) => {
    const targetCollaborator = resolveTargetCollaborator(conversationId);
    return targetCollaborator?.memory.referenceDocs ?? [];
  };

  const searchCollaboratorMemory = (
    query: string,
    mode?: MemorySearchMode,
    maxResults?: number,
    conversationId?: string | null
  ) => {
    const targetCollaborator = resolveTargetCollaborator(conversationId);
    if (!targetCollaborator) {
      return { ok: false as const, error: '当前没有可搜索记忆的协作者。' };
    }
    const normalizedQuery = query.trim();
    if (!normalizedQuery) {
      return { ok: false as const, error: '搜索记忆时缺少 query。' };
    }

    const conversations = recallConversations(targetCollaborator.id);
    const groups = mode === 'summary' ? [] : conversations
      .filter((candidate) => candidate.kind === 'group' && candidate.id !== conversationId)
      .sort((left, right) => right.updatedAt - left.updatedAt)
      .slice(0, 3);
    const result = searchCollaboratorMemorySources({
      query: normalizedQuery,
      mode,
      maxResults,
      summaries: targetCollaborator.memory.conversationSummaries,
      conversations,
      activeConversationId: conversationId ?? null,
      currentCollaboratorId: targetCollaborator.id
    });
    const summaryLines = result.summaries.map((summary, index) => [
      `${index + 1}. ${summary.title} · summaryId=${summary.id} · updated=${formatDate(summary.updatedAt)}`,
      `sourceConversationIds=${summary.sourceConversationIds.join(', ') || 'none'}`,
      `sourceMessageIds=${summary.sourceMessageIds.join(', ') || 'none'}`,
      `matched=${summary.matchedKeywords.join(', ') || 'exact'}`,
      excerptText(summary.content)
    ].join('\n'));
    const sourceLines = result.sources.map((source, index) => [
      `${index + 1}. ${source.title}`,
      `sourceConversationId=${source.conversationId}`,
      `sourceMessageIds=${source.sourceMessageIds.join(', ') || 'none'}`,
      `matched=${source.matchedKeywords.join(', ') || 'exact'}`,
      excerptText(source.text)
    ].join('\n'));
    const detailText = [
      `query=${normalizedQuery}`,
      summaryLines.length ? `## 总结候选\n${summaryLines.join('\n\n')}` : '## 总结候选\n没有匹配到摘要。',
      sourceLines.length ? `## 原文候选\n${sourceLines.join('\n\n')}` : '## 原文候选\n没有匹配到原文锚点。',
      ...(groups.length ? [
        '## 可读本机群聊目录（最近 3 个，目录不是正文）',
        ...groups.map((group) => `${group.title.slice(0, 80)} · sourceConversationId=${group.id}`),
        '群聊是 Polaris 本机原生能力，不是另一个 MCP。用 openMemorySource 传入上述 sourceConversationId、不填 sourceMessageIds，可实际读取本机最新保存的公开消息；无需另接群聊 MCP。'
      ] : []),
      '需要确认原文时，使用 openMemorySource 读取 sourceConversationId + sourceMessageIds。'
    ].join('\n\n');
    const total = result.summaries.length + result.sources.length;
    return {
      ok: true as const,
      summary: `已搜索记忆 · ${total} 个候选${groups.length ? ` · ${groups.length} 个群聊目录` : ''}`,
      detailText
    };
  };

  const openMemorySource = async (
    sourceConversationId: string,
    sourceMessageIds?: string[],
    maxChars?: number,
    conversationId?: string | null
  ) => {
    const targetCollaborator = resolveTargetCollaborator(conversationId);
    if (!targetCollaborator) return { ok: false as const, error: '当前没有可读取记忆原文的协作者。' };
    const sourceId = sourceConversationId.trim();
    let source = recallConversations(targetCollaborator.id).find((candidate) => candidate.id === sourceId);
    if (!source) return { ok: false as const, error: `没有找到可读取的记忆原文对话：${sourceId}` };
    if (source.messages.length === 0) {
      try {
        const loaded = store.chat.ensureConversationMessagesLoaded
          ? await store.chat.ensureConversationMessagesLoaded(sourceId)
          : { messages: await readConversationMessages(sourceId) };
        if (!loaded) return { ok: false as const, error: '本机原文加载失败，请稍后重试。' };
        // Recheck membership/recall switch after the asynchronous read.
        const latest = recallConversations(targetCollaborator.id).find((candidate) => candidate.id === sourceId);
        if (!latest) return { ok: false as const, error: '该对话当前未开放记忆召回。' };
        source = { ...latest, messages: latest.messages.length ? latest.messages : loaded.messages };
      } catch {
        return { ok: false as const, error: '本机原文加载失败，请稍后重试。' };
      }
    }
    const isGroup = source.kind === 'group';
    const publicMessages = isGroup ? source.messages.filter(isNaturalMemorySourceMessage) : source.messages;
    const latestGroupTail = isGroup && !sourceMessageIds?.length;
    const selectedMessages = latestGroupTail ? publicMessages.slice(-20) : publicMessages;
    const sourceLimit = isGroup
      ? Math.min(typeof maxChars === 'number' && Number.isFinite(maxChars) && maxChars >= 1 ? Math.floor(maxChars) : 4000, 8000)
      : maxChars;
    const opened = openConversationMemorySource({
      conversations: [{ ...source, messages: selectedMessages }],
      sourceConversationId: sourceId,
      sourceMessageIds,
      maxChars: sourceLimit,
      fromEnd: latestGroupTail
    });
    if (!opened) {
      return { ok: false as const, error: `没有找到记忆原文对话：${sourceConversationId}` };
    }
    const detailText = [
      `# ${opened.conversationTitle}`,
      `sourceConversationId=${opened.conversationId}`,
      `updated=${formatDate(opened.updatedAt)}`,
      ...(isGroup ? [`来源：本机群聊公开消息，读取时的快照；${latestGroupTail ? '最近最多 20 条，默认最多 4000 字。' : '按指定消息 ID 读取。'}这不是后台监听，也不是跨窗口代发。`] : []),
      opened.messages.length
        ? opened.messages.map((message) =>
            `[${formatDate(message.timestamp)}] ${memorySourceRoleLabel(message)} · messageId=${message.id}\n${message.content.trim()}`
          ).join('\n\n')
        : '这段原文没有可读取消息。',
      opened.truncated ? '（已按 maxChars 截断。）' : '',
      ...(latestGroupTail && publicMessages.length > selectedMessages.length ? ['（更早群聊消息未随本次读取发送。）'] : [])
    ].filter(Boolean).join('\n\n');
    return {
      ok: true as const,
      summary: `已打开记忆原文 · ${opened.conversationTitle}`,
      detailText
    };
  };

  const maybeHandleWriteMemoryAction = (
    target: WritableConversationBody,
    action: ToolAction,
    options?: {
      beforeMessageId?: string;
      sourceToolCallId?: string;
    }
  ) => {
    const conversationId = target.conversationId;
    if (action.kind !== 'writeMemory' && action.kind !== 'writeMemoryDoc') return false;

    if (action.kind === 'writeMemoryDoc') {
      const title = action.title.trim();
      const content = action.content.trim();
      if (!title || !content) return true;

      addRuntimeToolMessage(target, {
        id: createUid('tool'),
        kind: 'writeMemoryDoc',
        status: 'preview',
        title: action.docId ? '确认更新长期资料' : '确认写入长期资料',
        summary: `${action.docId ? '更新' : '新增'}长期资料 · ${title}`,
        memoryDocId: action.docId,
        memoryDocTitle: title,
        memoryDocSummary: action.summary,
        memoryDocContent: content,
        detailText: [
          `# ${title}`,
          action.summary?.trim() ? `摘要：${action.summary.trim()}` : '',
          content
        ].filter(Boolean).join('\n\n'),
        targetLabel: action.targetLabel,
        originMessageId: options?.beforeMessageId,
        toolCallId: options?.sourceToolCallId
      }, undefined, { beforeMessageId: options?.beforeMessageId });
      ui.setCommandStatus('这份长期资料需要确认后再写入。');
      return true;
    }

    const normalizedItems = buildWeightedMemoryLines(action.memory);
    if (normalizedItems.length === 0) return true;

    const { lowRisk, highRisk } = classifyMemoryWriteItems(normalizedItems);
    if (lowRisk.length > 0) {
      const didAppend = appendCollaboratorMemories(lowRisk, conversationId);
      addRuntimeToolMessage(target, {
        id: createUid('tool'),
        kind: 'writeMemory',
        status: didAppend ? 'executed' : 'failed',
        title: '写入记忆',
        summary: didAppend ? `已写入 ${lowRisk.length} 条低风险记忆` : '当前没有可写入记忆的协作者。',
        memoryItems: lowRisk,
        targetLabel: action.targetLabel,
        originMessageId: options?.beforeMessageId,
        toolCallId: options?.sourceToolCallId,
        error: didAppend ? undefined : '当前没有可写入记忆的协作者。'
      }, undefined, { beforeMessageId: options?.beforeMessageId });
    }

    if (highRisk.length === 0) return true;

    addRuntimeToolMessage(target, {
      id: createUid('tool'),
      kind: 'writeMemory',
      status: 'preview',
      title: '确认写入记忆',
      summary: memoryPreviewSummary(highRisk),
      memoryItems: highRisk,
      targetLabel: action.targetLabel,
      originMessageId: options?.beforeMessageId,
      toolCallId: options?.sourceToolCallId
    }, undefined, { beforeMessageId: options?.beforeMessageId });
    ui.setCommandStatus('检测到偏敏感记忆，等你确认后再写入。');
    return true;
  };

  const applyMemoryPreview = (target: WritableConversationBody, message: ChatMessage) => {
    const conversationId = target.conversationId;
    if (message.toolInvocation?.kind === 'writeMemoryDoc') {
      const didWrite = writeCollaboratorMemoryDoc({
        docId: message.toolInvocation.memoryDocId,
        title: message.toolInvocation.memoryDocTitle ?? '',
        summary: message.toolInvocation.memoryDocSummary ?? '',
        content: message.toolInvocation.memoryDocContent ?? ''
      }, conversationId);
      store.chat.updateMessage(target, message.id, {
        content: didWrite.ok ? `${didWrite.created ? '已写入' : '已更新'}当前协作者长期资料。` : '写入失败。',
        toolInvocation: {
          ...message.toolInvocation,
          status: didWrite.ok ? 'applied' : 'failed',
          memoryDocId: didWrite.ok ? didWrite.docId : message.toolInvocation.memoryDocId,
          memoryDocTitle: didWrite.ok ? didWrite.title : message.toolInvocation.memoryDocTitle,
          error: didWrite.ok ? undefined : didWrite.error
        }
      });
      ui.setCommandStatus(didWrite.ok ? '已确认写入长期资料。' : '写入长期资料失败。', !didWrite.ok);
      return true;
    }
    if (message.toolInvocation?.kind !== 'writeMemory') return false;
    const items = message.toolInvocation.memoryItems ?? [];
    if (!items.length) return false;

    const didAppend = appendCollaboratorMemories(items, conversationId);
    store.chat.updateMessage(target, message.id, {
      content: didAppend ? '已写入当前协作者记忆。' : '写入失败。',
      toolInvocation: {
        ...message.toolInvocation,
        status: didAppend ? 'applied' : 'failed',
        error: didAppend ? undefined : '当前没有可写入记忆的协作者。'
      }
    });
    ui.setCommandStatus(didAppend ? '已确认写入记忆。' : '写入记忆失败。', !didAppend);
    return true;
  };

  const rollbackMemoryPreview = (target: WritableConversationBody, message: ChatMessage) => {
    if (message.toolInvocation?.kind === 'writeMemoryDoc') {
      store.chat.updateMessage(target, message.id, {
        content: '这份长期资料没有写入。',
        toolInvocation: {
          ...message.toolInvocation,
          status: 'rolled_back'
        }
      });
      ui.setCommandStatus('已取消这份长期资料写入。');
      return true;
    }
    if (message.toolInvocation?.kind !== 'writeMemory') return false;

    store.chat.updateMessage(target, message.id, {
      content: '这批记忆没有写入。',
      toolInvocation: {
        ...message.toolInvocation,
        status: 'rolled_back'
      }
    });
    ui.setCommandStatus('已取消这批记忆写入。');
    return true;
  };

  return {
    appendCollaboratorMemories,
    writeCollaboratorMemoryDoc,
    readCollaboratorMemoryDoc,
    listCollaboratorMemoryDocs,
    searchCollaboratorMemory,
    openMemorySource,
    maybeHandleWriteMemoryAction,
    applyMemoryPreview,
    rollbackMemoryPreview
  };
}

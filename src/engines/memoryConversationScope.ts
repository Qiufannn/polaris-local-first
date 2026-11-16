import type { ChatMessage, Conversation } from '../types/domain';

export type MemoryConversationScope = Pick<Conversation, 'collaboratorId' | 'kind' | 'group' | 'groupRoomId'>;

/** Recall scope differs from the single-chat navigation/artifact scope. */
export function conversationMatchesMemoryRecallScope(
  conversation: MemoryConversationScope,
  collaboratorId: string | null | undefined
) {
  if (conversation.groupRoomId?.trim()) return false;
  if (conversation.kind === 'group' || conversation.group) {
    if (!collaboratorId || conversation.kind !== 'group' || !conversation.group) return false;
    return conversation.group.memoryRecallEnabled !== false
      && conversation.group.memberIds.includes(collaboratorId);
  }
  return !collaboratorId || conversation.collaboratorId === collaboratorId;
}

export function selectMemoryRecallConversations(conversations: Conversation[], collaboratorId: string) {
  return conversations.filter((conversation) => conversationMatchesMemoryRecallScope(conversation, collaboratorId));
}

export function memorySourceRoleLabel(message: Pick<ChatMessage, 'role' | 'assistantName' | 'speakerCollaboratorId'>) {
  if (message.role !== 'assistant') return message.role;
  const speaker = message.assistantName?.trim() || message.speakerCollaboratorId;
  return speaker ? `assistant (${speaker})` : 'assistant';
}

import { useEffect } from 'react';
import type { ChatUiState } from '../context/ChatUiState';

export function useResetUiStateOnConversationChange(
  activeConversationId: string | null,
  ui: ChatUiState
) {
  const {
    clearCommandStatus,
    setDragActive,
    setEditing,
    setPendingQuote,
    setCollapsedThinkingMessageIds,
    setSeenThinkingMessageIds,
    setThinkingSummaryMessageId,
    setExpandedCodeMessageIds
  } = ui;

  useEffect(() => {
    clearCommandStatus();
    setDragActive(false);
    setEditing(null);
    setPendingQuote(null);
    setCollapsedThinkingMessageIds([]);
    setSeenThinkingMessageIds([]);
    setThinkingSummaryMessageId(null);
    setExpandedCodeMessageIds([]);
  }, [
    activeConversationId,
    clearCommandStatus,
    setCollapsedThinkingMessageIds,
    setDragActive,
    setEditing,
    setPendingQuote,
    setExpandedCodeMessageIds,
    setSeenThinkingMessageIds,
    setThinkingSummaryMessageId
  ]);
}

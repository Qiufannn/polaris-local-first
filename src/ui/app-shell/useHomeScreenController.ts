import { useCallback, useState } from 'react';
import { usePersonaStore } from '../../stores/personaStore';
import { useSpaceStore } from '../../stores/spaceStore';
import { blurChatWorldFocus } from './chatWorldFocusHandoff';
import type { CollectionShelf } from '../../types/domain';

export type HomeDestination = 'chat' | 'room' | 'images' | 'works';
export function useHomeScreenController(onStatus: (text: string, isError?: boolean) => void) {
  // The launch screen is independent of the last persisted workspace.
  // Ephemeral state preserves browser-OAuth returns and phone resume.
  const [visible, setVisible] = useState(true);
  const scope = useSpaceStore(state => state.frontstageCollaboratorId);
  const personas = usePersonaStore(state => state.personas);
  const activeId = usePersonaStore(state => state.activeCollaboratorId);
  const persona = personas.find(item => item.id === scope) ?? personas.find(item => item.id === activeId) ?? personas[0] ?? null;
  const open = useCallback(() => { blurChatWorldFocus(typeof document === 'undefined' ? null : document.activeElement); setVisible(true); }, []);
  const enter = useCallback((destination: HomeDestination, notice?: string) => {
    const space = useSpaceStore.getState();
    if (persona) space.setFrontstageCollaboratorId(persona.id);
    if (destination === 'chat') space.setWorld('chat');
    else {
      const shelf: CollectionShelf = destination === 'images' ? 'image' : destination === 'works' ? 'code' : 'info';
      space.setCollectionShelf(shelf); space.setWorld('collection');
    }
    setVisible(false);
    if (notice) window.setTimeout(() => onStatus(notice, true), 0);
  }, [persona, onStatus]);
  return { visible, persona, open, enter };
}

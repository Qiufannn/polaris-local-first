import type { ChatMessage, PersonaSkill } from '../types/domain';
import type { AssistantRequestAudit } from './request/requestAudit';
import { resolvePersonaSkills } from './personaSkills';

export type PersonaSkillEvidence = { preparedAt: number; requestId: string; retention: 'kept' | 'dropped_budget' | 'disabled'; items: { name: string; status: string; missing: string[]; unresolved: string[] }[] };
const evidence = new Map<string, PersonaSkillEvidence>();
const listeners = new Set<() => void>();
export function subscribeSkillEvidence(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }
export function getSkillEvidence(personaId: string | null) { return personaId ? evidence.get(personaId) ?? null : null; }
export function recordSkillPreparation(audit: AssistantRequestAudit, skills: PersonaSkill[] | undefined, messages: ChatMessage[]) {
  if (!audit.collaboratorId || !skills?.length) return;
  const part = audit.truncation.promptParts.find(item => item.name === 'local_skill_context');
  evidence.set(audit.collaboratorId, { preparedAt: Date.now(), requestId: audit.requestId, retention: part?.status ?? 'disabled', items: resolvePersonaSkills(skills, messages).map(({ skill, status, missing, unresolved }) => ({ name: skill.name, status, missing, unresolved })) });
  if (evidence.size > 100) evidence.delete(evidence.keys().next().value!);
  listeners.forEach(listener => listener());
}

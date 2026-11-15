import type { AssistantToolContext } from '../assistantToolProtocol';
import type { ProviderCapabilityPromptInjection } from '../provider-runtime';
import type { AssistantPromptPart, PersonaRuntimePromptSource } from './requestAudit';
import type { TemplateContext } from '../templateEngine';
import type { ChatMessage, ConversationTaskState } from '../../types/domain';
import { buildRegexTriggerContext } from '../regexTriggerProcessor';
import { buildPersonaSkillContext } from '../personaSkills';
import type { PersonaSkill } from '../../types/domain';
import { buildCapabilityEntries } from './requestPromptCapabilities';
import { buildIdentityEntries } from './requestPromptIdentity';
import { buildModelRuntimeEntry, buildRuntimeClockEntry, buildWorkRuntimeEntry } from './requestPromptRuntime';
import { buildSystemIdentityEntries } from './requestPromptSystemIdentity';
import type { AssistantToolPromptProtocolMode } from '../tool-protocol/assistantToolProtocolPrompt';

export function buildAssistantPromptLayers(params: {
  personaPrompt: string;
  personaPromptSource: PersonaRuntimePromptSource;
  templateContext: TemplateContext;
  messages: ChatMessage[];
  regexTriggers?: string;
  skills?: PersonaSkill[];
  currentTask?: ConversationTaskState | null;
  includeRuntimeClockContext?: boolean;
  promptInjections?: ProviderCapabilityPromptInjection[];
  toolContext?: AssistantToolContext;
  toolProtocolMode?: AssistantToolPromptProtocolMode;
}): string[] {
  return buildAssistantPromptParts(params)
    .filter((part) => part.enabled)
    .map((part) => part.content);
}

export function buildAssistantPromptParts(params: {
  personaPrompt: string;
  personaPromptSource: PersonaRuntimePromptSource;
  templateContext: TemplateContext;
  messages: ChatMessage[];
  regexTriggers?: string;
  skills?: PersonaSkill[];
  currentTask?: ConversationTaskState | null;
  includeRuntimeClockContext?: boolean;
  promptInjections?: ProviderCapabilityPromptInjection[];
  toolContext?: AssistantToolContext;
  toolProtocolMode?: AssistantToolPromptProtocolMode;
}): AssistantPromptPart[] {
  const { personaPrompt, personaPromptSource, templateContext, messages, regexTriggers, currentTask, includeRuntimeClockContext, promptInjections, toolContext, toolProtocolMode } = params;
  const systemIdentityEntries = buildSystemIdentityEntries();
  const identityEntries = buildIdentityEntries({
    personaPrompt,
    personaPromptSource,
    templateContext
  });
  const modelRuntimeEntry = buildModelRuntimeEntry({ promptInjections, toolContext });
  const runtimeClockEntry = includeRuntimeClockContext ? buildRuntimeClockEntry(templateContext) : null;
  const regexTriggerEntry = {
    name: 'regex_trigger_context' as const,
    label: '正则触发',
    role: 'system' as const,
    layer: 'context' as const,
    truncationPriority: 52,
    content: buildRegexTriggerContext(messages, regexTriggers),
    enabled: false,
    charCount: 0
  };
  const workRuntimeEntry = buildWorkRuntimeEntry({ currentTask, messages, toolContext });
  const capabilityEntries = buildCapabilityEntries({ messages, toolContext, toolProtocolMode });

  return [
    ...systemIdentityEntries,
    ...identityEntries,
    ...capabilityEntries,
    ...(runtimeClockEntry ? [runtimeClockEntry] : []),
    ...(modelRuntimeEntry ? [modelRuntimeEntry] : []),
    regexTriggerEntry,
    { name: 'local_skill_context', label: '本地技能', role: 'system', layer: 'context', truncationPriority: 53, content: buildPersonaSkillContext(params.skills, messages), enabled: false, charCount: 0 } as const,
    ...(workRuntimeEntry ? [workRuntimeEntry] : [])
  ].map((part) => ({
    ...part,
    enabled: Boolean(part.content),
    charCount: part.content.length
  }));
}

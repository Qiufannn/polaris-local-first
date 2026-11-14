import { isChatgptPlanProvider, chatgptPlanError } from '../chatgptPlan';
import { openAiResponsesAdapter } from './providerRuntimeResponsesAdapter';
import { buildOrderedMessages, extractTextPayload } from './requestShared/messages';
import { buildToolResultPayloadText } from './requestShared/toolResultPayload';
import { resolveProviderEffectiveModel } from './internal/providerEffectiveProfile';
import { resolveProviderCapability, canonicalProviderCapabilitiesFromContract } from './providerCapability';
import type { ProviderRuntimeRequestAdapter, ProviderRuntimeRequestInput } from './providerRuntimeRequestTypes';
import type { CanonicalProviderStreamEvent, ProviderHttpRequest } from './providerRuntimeTypes';
import { parseOpenAiResponsesStreamEvents } from './providerRuntimeStreamEvents';

export function buildChatgptPlanRequest({ api, context, advanced }: ProviderRuntimeRequestInput): ProviderHttpRequest {
  const model = resolveProviderEffectiveModel(api, advanced?.modelOverride).trim();
  if (!model) throw new Error('请先登录 ChatGPT，并选择该账号可用的订阅模型。');
  const input: Record<string, unknown>[] = [];
  for (const message of buildOrderedMessages(context, {
    collapseSystemMessages: false, deferVolatileSystemMessages: false, omitVolatileSystemMessages: false
  })) {
    if (message.role === 'tool' && message.toolResult) {
      input.push({ type: 'function_call_output', call_id: message.toolResult.toolCallId,
        output: buildToolResultPayloadText(message, { toolName: message.toolResult.toolName, kind: message.toolResult.toolName }) });
      continue;
    }
    const role = message.role === 'system' ? 'developer' : message.role;
    if (role === 'tool') continue;
    const content = typeof message.content === 'string' ? message.content : message.content.map(part =>
      part.type === 'text'
        ? { type: role === 'assistant' ? 'output_text' : 'input_text', text: part.text }
        : role === 'user' ? { type: 'input_image', image_url: part.image_url.url } : null
    ).filter(Boolean);
    if (extractTextPayload(message.content).trim() || (Array.isArray(content) && content.length)) input.push({ role, content });
    for (const call of message.role === 'assistant' ? message.toolCalls ?? [] : []) {
      input.push({ type: 'function_call', namespace: 'polaris', call_id: call.id,
        name: call.name.replace(/^polaris\./, ''), arguments: call.argumentsText });
    }
  }
  // Construct from an allowlist, ignoring custom headers/body and generic transport overrides.
  const body: Record<string, unknown> = { model, input, store: false, stream: true };
  if (context.tools?.length) {
    body.tools = [{ type: 'namespace', name: 'polaris', description: 'Polaris local tools', tools: context.tools.map(tool => ({
      type: 'function', name: tool.function.name, description: tool.function.description, parameters: tool.function.parameters
    })) }];
    body.tool_choice = context.toolChoice ?? 'auto';
  }
  return {
    endpoint: 'https://api.openai.com/v1/responses', headers: { 'Content-Type': 'application/json' },
    body, provider: 'openai-responses', authentication: 'chatgpt-plan', compatibilityMode: 'standard',
    capability: { route: { isBuiltInTrial: false }, transport: { relayAllowedWhenNetworkFails: false } }
  };
}

export function createChatgptPlanStreamParser() {
  let completed = false;
  let terminal = false;
  const callIds = new Map<string, string>();
  return {
    parse(payload: unknown): CanonicalProviderStreamEvent[] {
      if (!payload || typeof payload !== 'object' || terminal) return [];
      const value = payload as Record<string, any>;
      const type = value.type;
      if (type === 'response.failed' || type === 'error') {
        terminal = true;
        throw new Error(chatgptPlanError(String(value.response?.error?.code ?? value.error?.code ?? value.code ?? '')));
      }
      if (type === 'response.incomplete') {
        terminal = true;
        throw new Error('ChatGPT 回复未完成，已收到的内容仍保留，请稍后重试。');
      }
      if (type === 'response.output_item.added' && value.item?.type === 'function_call') {
        if (value.item.id && value.item.call_id) callIds.set(value.item.id, value.item.call_id);
      }
      const normalized = value.item_id && callIds.has(value.item_id) ? { ...value, item_id: callIds.get(value.item_id) } : value;
      const events = parseOpenAiResponsesStreamEvents(normalized).map(event =>
        event.type === 'tool_call.start' || event.type === 'tool_call.done'
          ? { ...event, name: event.name.replace(/^polaris\./, '') } : event);
      if (type === 'response.completed') { completed = true; terminal = true; }
      return events;
    },
    assertCompleted() {
      if (!completed) throw new Error('ChatGPT 连接中断，尚未收到完整回复确认；已收到的内容仍保留。');
    }
  };
}

export const chatgptPlanAdapter: ProviderRuntimeRequestAdapter = {
  ...openAiResponsesAdapter,
  id: 'chatgpt-plan', label: 'ChatGPT 订阅',
  match: profile => isChatgptPlanProvider(profile) ? { adapterId: 'chatgpt-plan', confidence: 'exact', reason: 'explicit ChatGPT plan authentication' } : null,
  resolveCapabilities: ({ provider, advanced }) => canonicalProviderCapabilitiesFromContract(resolveProviderCapability(provider, advanced)),
  resolveRetry: () => ({ kind: 'none', reason: 'Subscription requests never fall back to a paid, relayed or non-streaming route.' }),
  prepareConnectionTestRequest: () => undefined,
  buildRequest: buildChatgptPlanRequest
};

import { describe, expect, it, vi } from 'vitest';
import { buildChatgptPlanRequest, chatgptPlanAdapter, createChatgptPlanStreamParser } from './providerRuntimeChatgptPlanAdapter';
import { createProviderRuntimeAdvanced, createProviderRuntimeTestContext, createProviderRuntimeTestProvider } from './providerRuntimeFixtures';
import { resolveProviderRuntimeRequestAdapter } from './providerRuntimeAdapters';
import { createStreamingReplyCollector } from '../chat-api/chatApiStreamingCollector';
import { resolveCanonicalProviderCapabilities } from './providerRuntimeCapabilities';
import { createCustomProviderProfile, mergeProviderPatch } from '../../stores/runtimeStoreProviders';
import { parseProviderRouteCard, serializeProviderRouteCard } from '../providerRouteCard';
import type { ProviderRuntimeRetryInput } from './providerRuntimeRequestTypes';

function provider() { return createProviderRuntimeTestProvider({ authKind: 'chatgpt-plan', protocol: 'openai-responses' }); }
function request() { return buildChatgptPlanRequest({ api: provider(), context: createProviderRuntimeTestContext({ withImage: true, withToolHistory: true }) }); }

describe('ChatGPT subscription request boundary', () => {
  it('selects a distinct adapter and cannot be redirected or reconfigured into a paid request', () => {
    const api = { ...provider(), baseUrl: 'https://other.invalid', path: '/other' };
    const result = buildChatgptPlanRequest({ api, context: createProviderRuntimeTestContext(),
      advanced: createProviderRuntimeAdvanced({ streaming: false, customHeaders: '{"Authorization":"untrusted"}',
        customBody: '{"store":true,"stream":false,"temperature":1,"previous_response_id":"previous","input":[],"model":"other"}' }),
      bodyOverrides: { store: true, stream: false, background: true, max_output_tokens: 99 }
    });
    expect(resolveProviderRuntimeRequestAdapter(api).id).toBe('chatgpt-plan');
    expect(result.endpoint).toBe('https://api.openai.com/v1/responses');
    expect(result.body).toMatchObject({ store: false, stream: true, model: api.model });
    expect(Object.keys(result.body).sort()).toEqual(['input', 'model', 'store', 'stream', 'tool_choice', 'tools']);
    expect(JSON.stringify(result)).not.toContain(api.apiKey);
    expect(result.headers).toEqual({ 'Content-Type': 'application/json' });
    expect(result.capability?.transport.relayAllowedWhenNetworkFails).toBe(false);
  });

  it('preserves developer instructions, image input and native function call/result pairing', () => {
    const result = request();
    const input = result.body.input as Record<string, any>[];
    expect(input[0].role).toBe('developer');
    expect(input.some(item => item.role === 'system')).toBe(false);
    expect(input.find(item => item.role === 'user')?.content).toContainEqual({ type: 'input_image', image_url: 'data:image/png;base64,ZmFrZQ==' });
    expect(input).toContainEqual(expect.objectContaining({ type: 'function_call', call_id: 'call-runtime-1', name: 'patchRawCss', namespace: 'polaris' }));
    expect(input).toContainEqual(expect.objectContaining({ type: 'function_call_output', call_id: 'call-runtime-1' }));
    expect(result.body.tools).toEqual([expect.objectContaining({ type: 'namespace', name: 'polaris', tools: [expect.objectContaining({ type: 'function', name: 'patchRawCss' })] })]);
    expect(JSON.stringify(result)).not.toContain('[assistant_tool_calls]');
  });

  it('never replays historical reasoning or emits output-token overrides in connection tests', () => {
    const context = createProviderRuntimeTestContext();
    context.segments[0].messages.push({ role: 'assistant', content: 'retained answer', thinkingText: 'old private reasoning' });
    const result = buildChatgptPlanRequest({ api: provider(), context });
    chatgptPlanAdapter.prepareConnectionTestRequest({ request: result, provider: provider(), maxOutputTokens: 10 });
    expect(JSON.stringify(result)).not.toContain('old private reasoning');
    expect(result.body).not.toHaveProperty('max_output_tokens');
    expect(resolveCanonicalProviderCapabilities(provider(), { streaming: false }).transport.modes).toEqual(['native-direct']);
    expect(resolveCanonicalProviderCapabilities(provider()).budgets.outputTokenField).toBeNull();
    expect(chatgptPlanAdapter.resolveRetry({} as ProviderRuntimeRetryInput).kind).toBe('none');
  });

  it('keeps the authentication kind through normal persistence while dropping credentials and identity', () => {
    const imported = createCustomProviderProfile(JSON.parse(JSON.stringify({ ...provider(), email: 'private@example.invalid', access_token: 'not-a-real-secret' })));
    expect(imported.authKind).toBe('chatgpt-plan'); expect(imported.apiKey).toBe('');
    expect(imported).not.toHaveProperty('email'); expect(imported).not.toHaveProperty('access_token');
    expect(mergeProviderPatch(imported, { apiKey: 'must-not-be-saved' }).apiKey).toBe('');
    const card = serializeProviderRouteCard(provider(), { includeApiKey: true });
    expect(card).not.toContain('test-key');
    expect(parseProviderRouteCard(card)).toMatchObject({ authKind: 'chatgpt-plan', apiKey: '' });
  });
});

describe('strict subscription SSE terminal handling', () => {
  it('requires response.completed even after partial text and a DONE marker', () => {
    const parser = createChatgptPlanStreamParser();
    const progress = vi.fn();
    const collector = createStreamingReplyCollector('account-model', progress, parser.parse);
    collector.pushTextChunk('data: {"type":"response.output_text.delta","delta":"你好"}\n\ndata: [DONE]\n\n', true);
    expect(collector.finish().content).toBe('你好'); expect(progress).toHaveBeenCalled();
    expect(() => parser.assertCompleted()).toThrow('连接中断');
  });
  it('accepts a complete response across arbitrary SSE boundaries', () => {
    const parser = createChatgptPlanStreamParser();
    const collector = createStreamingReplyCollector('account-model', undefined, parser.parse);
    const text = 'data: {"type":"response.output_text.delta","delta":"中文回复"}\n\ndata: {"type":"response.completed","response":{"model":"account-model","output":[]}}\n\n';
    for (let i = 0; i < text.length; i += 3) collector.pushTextChunk(text.slice(i, i + 3), true);
    expect(collector.finish().content).toBe('中文回复'); expect(() => parser.assertCompleted()).not.toThrow();
  });
  it.each(['response.failed', 'response.incomplete', 'error'])('treats %s as a recoverable failure instead of success', type => {
    const parser = createChatgptPlanStreamParser();
    parser.parse({ type: 'response.output_text.delta', delta: 'partial' });
    expect(() => parser.parse({ type, response: { error: { code: 'subscription_sharing_usage_limit_exceeded' } } })).toThrow();
    expect(() => parser.assertCompleted()).toThrow();
  });
  it('maps streamed quota failures to Chinese without exposing raw server details', () => {
    const parser = createChatgptPlanStreamParser();
    expect(() => parser.parse({ type: 'response.failed', response: { error: {
      code: 'subscription_sharing_usage_limit_exceeded', message: 'untrusted server detail'
    } } })).toThrow('订阅额度已用尽');
  });
  it('joins item IDs to call IDs for streamed tool arguments', () => {
    const parser = createChatgptPlanStreamParser();
    expect(parser.parse({ type: 'response.output_item.added', item: { type: 'function_call', id: 'item-a', call_id: 'call-a', name: 'polaris.patchRawCss' } }))
      .toContainEqual({ type: 'tool_call.start', id: 'call-a', name: 'patchRawCss' });
    expect(parser.parse({ type: 'response.function_call_arguments.delta', item_id: 'item-a', delta: '{' }))
      .toContainEqual({ type: 'tool_call.delta', id: 'call-a', argumentsDelta: '{' });
  });
});

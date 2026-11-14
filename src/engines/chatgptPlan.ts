import type { ProviderProfile } from '../types/domain';

export function isChatgptPlanProvider(provider: Pick<ProviderProfile, 'authKind'>) {
  return provider.authKind === 'chatgpt-plan';
}

export function chatgptPlanProvider(model: string): Partial<ProviderProfile> {
  return {
    name: 'ChatGPT 订阅', authKind: 'chatgpt-plan', protocol: 'openai-responses',
    baseUrl: 'https://api.openai.com/v1', path: '/responses', apiKey: '', model,
    capabilities: { images: true, streaming: true, thinking: true }
  };
}

export function chatgptPlanError(code: string) {
  if (code === 'subscription_sharing_usage_limit_exceeded') return 'ChatGPT 订阅额度已用尽，请等待额度恢复；不会自动切换付费线路。';
  if (code === 'subscription_sharing_usage_unavailable') return '当前账号或工作区暂不能使用订阅额度，请检查订阅资格或重新登录。';
  if (code === 'insufficient_scope' || code === 'invalid_token') return 'ChatGPT 授权已失效或权限不足，请重新登录。';
  return 'ChatGPT 订阅回复失败，请稍后重试；已收到的本地内容仍保留。';
}

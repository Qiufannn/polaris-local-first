import { Capacitor, registerPlugin } from '@capacitor/core';

export type McpSiteIdentity = { ref: string; connected: boolean; scopeGranted: boolean; email?: string };
export type McpSiteStatus = { accounts: McpSiteIdentity[]; activeRef: string };
export type McpSiteAddress = { serverId: string; url: string };
type Bridge = {
  status(args: McpSiteAddress): Promise<McpSiteStatus>;
  login(args: McpSiteAddress & { accountRef?: string }): Promise<McpSiteStatus>;
  cancelLogin(): Promise<void>;
  disconnect(args: McpSiteAddress): Promise<{ revocationConfirmed: boolean }>;
  request(args: McpSiteAddress & { method: string; body: string }): Promise<{ status: number; data: string }>;
};
let bridge: Bridge | undefined;
export function canUseMcpSiteAuth() {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'
    && typeof Capacitor.isPluginAvailable === 'function' && Capacitor.isPluginAvailable('McpSiteAuth');
}
function native(): Bridge {
  if (!canUseMcpSiteAuth()) throw new Error('请使用包含小家独立授权功能的 Android 更新版；此连接不会回退到普通网络请求。');
  return bridge ??= registerPlugin<Bridge>('McpSiteAuth');
}
export const mcpSiteAuth: Bridge = {
  status: args => native().status(args), login: args => native().login(args),
  cancelLogin: () => native().cancelLogin(), disconnect: args => native().disconnect(args),
  request: args => native().request(args)
};

import { Capacitor, registerPlugin } from '@capacitor/core';
type Bridge = {
  open(args: { url: string; signIn?: boolean }): Promise<{ action: 'chat' | 'closed' }>;
  request(args: { url: string; method: string; body: string; headers?: Record<string, string> }): Promise<{ status: number; data: string }>;
};
let bridge: Bridge | undefined;
export function canUseOriginalHome() { return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android' && Capacitor.isPluginAvailable('OriginalHome'); }
function native() { if (!canUseOriginalHome()) throw new Error('需要支持原小屋独立浏览器的 Android 更新版。'); return bridge ??= registerPlugin<Bridge>('OriginalHome'); }
export const originalHome: Bridge = { open: async args => native().open(args), request: async args => native().request(args) };
export function originalHomeOrigin(value: string) {
  try { const url = new URL(value); if (url.protocol === 'https:' && url.hostname.endsWith('.chatgpt.site') && !url.username && !url.password && !url.port && !url.search && !url.hash && (url.pathname === '/' || url.pathname === '/mcp')) return url.origin; } catch { /* invalid input */ }
  return '';
}
export function originalHomeEndpoint(value: string) { const origin = originalHomeOrigin(value); return origin ? `${origin}/mcp` : ''; }

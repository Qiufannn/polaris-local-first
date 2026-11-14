import { Capacitor, registerPlugin } from '@capacitor/core';

export type ChatgptAccount = {
  ref: string; email: string; subject: string; registration: string;
  connected: boolean; planGranted: boolean;
};
export type ChatgptStatus = { accounts: ChatgptAccount[]; activeRef: string };
export type ChatgptModel = { id: string; label: string };
type AuthPlugin = {
  status(): Promise<ChatgptStatus>;
  login(options: { accountRef?: string }): Promise<ChatgptStatus>;
  cancelLogin(): Promise<void>;
  select(options: { accountRef: string }): Promise<ChatgptStatus>;
  disconnect(): Promise<{ revocationConfirmed: boolean }>;
  models(): Promise<{ models: ChatgptModel[] }>;
  resetLocalConnections(): Promise<ChatgptStatus>;
};
const native = registerPlugin<AuthPlugin>('ChatGptAuth');

export function canUseChatgptAuth() {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android' && Capacitor.isPluginAvailable('ChatGptAuth');
}

function requireAndroid() {
  if (!canUseChatgptAuth()) throw new Error('ChatGPT 订阅登录需要包含原生授权桥的 Android App。');
}

// These summaries belong in component memory only, never the runtime/backup store.
export const chatgptAuth = {
  status() { requireAndroid(); return native.status(); },
  login(accountRef?: string) { requireAndroid(); return native.login({ accountRef }); },
  cancelLogin() { requireAndroid(); return native.cancelLogin(); },
  select(accountRef: string) { requireAndroid(); return native.select({ accountRef }); },
  disconnect() { requireAndroid(); return native.disconnect(); },
  models() { requireAndroid(); return native.models(); },
  resetLocalConnections() { requireAndroid(); return native.resetLocalConnections(); }
};

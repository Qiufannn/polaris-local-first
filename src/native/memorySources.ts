import { Capacitor, registerPlugin } from '@capacitor/core';
export type MemoryAddress = { serverId: string; url: string; authKind: 'notion-memory-oauth' | 'github-memory-device' | 'garden-oauth'; publicClientId?: string };
export type MemoryStatus = { connected: boolean; provider: string; writingEnabled?: boolean; diaryParentUrl?: string; diaryTemplateUrl?: string; pending?: boolean; userCode?: string; pollAfterSeconds?: number };
type RequestArgs = MemoryAddress & { method: string; body: string; headers?: Record<string, string>; userWriteRequested?: boolean };
type Bridge = {
  status(args: MemoryAddress): Promise<MemoryStatus>;
  setWritingEnabled(args: MemoryAddress & { enabled: boolean }): Promise<MemoryStatus>;
  setDiaryWriting(args: MemoryAddress & { enabled: boolean; parentUrl: string; templateUrl: string }): Promise<MemoryStatus>;
  notionLogin(args: MemoryAddress): Promise<MemoryStatus>;
  githubStart(args: MemoryAddress): Promise<{ userCode: string; verificationUri: string; pollAfterSeconds: number }>;
  githubPoll(args: MemoryAddress): Promise<{ state: 'connected' | 'pending'; pollAfterSeconds: number; message?: string }>;
  openGithub(): Promise<void>;
  cancel(args: MemoryAddress): Promise<void>;
  disconnect(args: MemoryAddress): Promise<{ revocationConfirmed: boolean }>;
  request(args: RequestArgs): Promise<{ status: number; data: string; headers: Record<string, string> }>;
};
let bridge: Bridge | undefined;
export function canUseMemorySources() { return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android' && typeof Capacitor.isPluginAvailable === 'function' && Capacitor.isPluginAvailable('MemorySources'); }
function native() {
  if (!canUseMemorySources()) throw new Error('需要包含花园与 GitHub / Notion 记忆连接的 Android 更新版；未使用其他网络线路。');
  return bridge ??= registerPlugin<Bridge>('MemorySources');
}
export const memorySources: Bridge = {
  setWritingEnabled: a => native().setWritingEnabled(a),
  setDiaryWriting: a => native().setDiaryWriting(a),
  status: a => native().status(a), notionLogin: a => native().notionLogin(a), githubStart: a => native().githubStart(a), githubPoll: a => native().githubPoll(a),
  openGithub: () => native().openGithub(), cancel: a => native().cancel(a), disconnect: a => native().disconnect(a), request: a => native().request(a)
};

import type { McpServerConfig } from '../types/domain';
export const MCP_AUTH_KINDS = ['sites-memory-oauth', 'notion-memory-oauth', 'github-memory-device', 'garden-oauth', 'original-home-session'] as const;
export function isProtectedMcp(server: Pick<McpServerConfig, 'authKind'>) { return MCP_AUTH_KINDS.some(kind => kind === server.authKind); }
export function isPublicGithubClientId(value: string) { return /^Iv[A-Za-z0-9_.-]{10,118}$/.test(value); }

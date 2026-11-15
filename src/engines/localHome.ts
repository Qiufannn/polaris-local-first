export function normalizeHomeUrl(value: unknown): string {
  if (typeof value !== 'string' || !value.trim() || value.length > 2000) return '';
  try { const url = new URL(value.trim()); return url.protocol === 'https:' && !url.username && !url.password ? url.href : ''; } catch { return ''; }
}

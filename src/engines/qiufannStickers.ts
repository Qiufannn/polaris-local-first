import manifest from '../config/qiufannStickers.json';
import { createStoredAttachment } from '../infrastructure/assetStore';

export type QiufannSticker = (typeof manifest)[number];
export const qiufannStickers: readonly QiufannSticker[] = manifest;
export function searchQiufannStickers(query: string) {
  const terms = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  return qiufannStickers.filter(sticker => terms.every(term => `${sticker.id} ${sticker.name} ${sticker.labels.join(' ')}`.toLocaleLowerCase().includes(term)));
}
export function stickerAssetPath(sticker: QiufannSticker) {
  const original = qiufannStickers.find(entry => entry.id === sticker.id);
  if (!original) throw new Error('这张表情包不在当前图库中。');
  const extension = original.imageUrl.endsWith('.png') ? 'png' : 'jpg';
  return `/qiufann-stickers/${original.id}.${extension}`;
}
/** Preserve the bundled original bytes in the normal attachment store and backup flow. */
export async function createQiufannStickerAttachment(sticker: QiufannSticker, signal?: AbortSignal) {
  const original = qiufannStickers.find(entry => entry.id === sticker.id);
  if (!original) throw new Error('这张表情包不在当前图库中。');
  const response = await fetch(stickerAssetPath(original), { signal, credentials: 'omit' });
  if (!response.ok) throw new Error('没有读到表情包原图，请重新打开面板再试。');
  const bytes = await response.arrayBuffer();
  const signature = new Uint8Array(bytes);
  const png = original.imageUrl.endsWith('.png');
  if (!bytes.byteLength || bytes.byteLength > 1024 * 1024 || (png
    ? ![137, 80, 78, 71, 13, 10, 26, 10].every((value, index) => signature[index] === value)
    : !(signature[0] === 255 && signature[1] === 216 && signature[2] === 255))) throw new Error('表情包原图不完整，未加入待发送栏。');
  signal?.throwIfAborted();
  const mimeType = png ? 'image/png' : 'image/jpeg';
  return createStoredAttachment({ kind: 'image', name: `表情包-${original.name}.${png ? 'png' : 'jpg'}`, mimeType,
    blob: new Blob([bytes], { type: mimeType }), textContent: `表情包：${original.name}；标签：${original.labels.join('、')}` });
}

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
const mocks = vi.hoisted(() => ({ create: vi.fn() }));
vi.mock('../infrastructure/assetStore', () => ({ createStoredAttachment: mocks.create }));
import { createQiufannStickerAttachment, qiufannStickers, searchQiufannStickers, stickerAssetPath } from './qiufannStickers';
describe('owner sticker library', () => {
  beforeEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); mocks.create.mockResolvedValue({ id: 'unit', assetId: 'unit-asset', kind: 'image' }); });
  it('ships all 101 unique original JPEG/PNG files, including transparency', () => {
    expect(qiufannStickers).toHaveLength(101); expect(new Set(qiufannStickers.map(x => x.id)).size).toBe(101);
    for (const sticker of qiufannStickers) {
      const bytes = readFileSync(`public${stickerAssetPath(sticker)}`);
      expect(bytes.length).toBeGreaterThan(0);
      expect(bytes.subarray(0, 3).toString('hex')).toBe(sticker.imageUrl.endsWith('.png') ? '89504e' : 'ffd8ff');
    }
  });
  it('searches names, IDs and tags with multiple terms', () => {
    expect(searchQiufannStickers('')).toHaveLength(101);
    expect(searchQiufannStickers('哭哭 委屈').some(x => x.id === '002')).toBe(true);
    expect(searchQiufannStickers('001')[0].name).toBe('自嘲熊点赞');
    expect(searchQiufannStickers('不存在的表情包')).toEqual([]);
  });
  it('takes the bundled path from the catalogue instead of trusting a supplied URL', () => {
    expect(stickerAssetPath({ ...qiufannStickers[0], imageUrl: 'https://elsewhere.invalid/secret' })).toBe('/qiufann-stickers/001.jpg');
    expect(() => stickerAssetPath({ ...qiufannStickers[0], id: '../secret' })).toThrow();
  });
  it('stores original bytes as an ordinary backup-compatible image attachment without external requests', async () => {
    const bytes = readFileSync('public/qiufann-stickers/001.jpg'); const request = vi.fn().mockResolvedValue(new Response(bytes)); vi.stubGlobal('fetch', request);
    await createQiufannStickerAttachment(qiufannStickers[0]);
    expect(request).toHaveBeenCalledWith('/qiufann-stickers/001.jpg', expect.objectContaining({ credentials: 'omit' }));
    const stored = mocks.create.mock.calls[0][0]; expect(stored.kind).toBe('image'); expect(stored.mimeType).toBe('image/jpeg');
    expect(Buffer.from(await stored.blob.arrayBuffer())).toEqual(bytes); expect(stored.textContent).toContain('自嘲熊点赞');
  });
  it('does not add a broken or cancelled image', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('invalid image')));
    await expect(createQiufannStickerAttachment(qiufannStickers[0])).rejects.toThrow('原图不完整'); expect(mocks.create).not.toHaveBeenCalled();
    const controller = new AbortController(); controller.abort();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(readFileSync('public/qiufann-stickers/001.jpg'))));
    await expect(createQiufannStickerAttachment(qiufannStickers[0], controller.signal)).rejects.toThrow(); expect(mocks.create).not.toHaveBeenCalled();
  });
});

import { describe, expect, it } from 'vitest';
import {
  DEFAULT_DISPLAY_PREFERENCES,
  mergeDisplayPreferencesPatch,
  normalizeDisplayPreferences
} from './spaceStoreDisplayPreferences';

describe('normalizeDisplayPreferences', () => {
  it('uses the default display preferences when no payload exists', () => {
    expect(normalizeDisplayPreferences()).toEqual(DEFAULT_DISPLAY_PREFERENCES);
  });

  it('preserves the haptics switch and clamps font scale', () => {
    expect(normalizeDisplayPreferences({
      appearance: 'dark',
      hapticsEnabled: false,
      fontScale: 9
    })).toEqual({
      appearance: 'dark',
      hapticsEnabled: false,
      fontScale: 1.5,
      chatActivityDisplay: 'collapsed'
    });
  });

  it('falls back to system appearance for unknown values', () => {
    expect(normalizeDisplayPreferences({
      appearance: 'night',
      hapticsEnabled: true,
      fontScale: 1
    })).toEqual({
      appearance: 'system',
      hapticsEnabled: true,
      fontScale: 1,
      chatActivityDisplay: 'collapsed'
    });
  });
});

describe('mergeDisplayPreferencesPatch', () => {
  it('normalizes patched display preferences', () => {
    expect(mergeDisplayPreferencesPatch(DEFAULT_DISPLAY_PREFERENCES, {
      appearance: 'light',
      fontScale: 0.2
    })).toEqual({
      appearance: 'light',
      hapticsEnabled: false,
      fontScale: 0.9,
      chatActivityDisplay: 'collapsed'
    });
  });
});

describe('activity display persistence',()=>{it('round-trips every display mode and defaults unknown modes',()=>{for(const chatActivityDisplay of ['full','collapsed','hidden']as const){expect(normalizeDisplayPreferences(JSON.parse(JSON.stringify(mergeDisplayPreferencesPatch(DEFAULT_DISPLAY_PREFERENCES,{chatActivityDisplay}))))).toMatchObject({chatActivityDisplay});}expect(normalizeDisplayPreferences({chatActivityDisplay:'unknown'}).chatActivityDisplay).toBe('collapsed');});});

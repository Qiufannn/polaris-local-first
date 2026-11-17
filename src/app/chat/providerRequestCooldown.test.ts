import { describe, expect, it, vi } from 'vitest';
import { ProviderRequestCooldown } from './providerRequestCooldown';

describe('provider request cooldown', () => {
  it('blocks repeated private/group calls to the same provider after 429 without sending or sleeping', async () => {
    let now = 0;
    const cooldown = new ProviderRequestCooldown(() => now);
    const request = vi.fn(async () => ({ status: 'failed' as const, rateLimited: true }));
    const blocked = vi.fn();
    await cooldown.run('pet-api', request);
    for (let i = 0; i < 5; i++) {
      expect(await cooldown.run('pet-api', request, blocked)).toEqual({ status: 'failed', rateLimited: true, retryAfterMs: 60_000 });
    }
    expect(request).toHaveBeenCalledTimes(1);
    expect(blocked).toHaveBeenCalledTimes(5);
    now = 60_000;
    await cooldown.run('pet-api', request);
    expect(request).toHaveBeenCalledTimes(2);
    expect(cooldown.remainingMs('pet-api')).toBe(120_000);
  });

  it('keeps other providers usable and resets the failure streak on a real successful recovery', async () => {
    let now = 0;
    const cooldown = new ProviderRequestCooldown(() => now);
    await cooldown.run('pet-api', async () => ({ status: 'failed', rateLimited: true }));
    const brother = vi.fn(async () => ({ status: 'completed' as const }));
    await cooldown.run('subscription-api', brother);
    expect(brother).toHaveBeenCalledTimes(1);
    now = 60_000;
    await cooldown.run('pet-api', brother);
    await cooldown.run('pet-api', async () => ({ status: 'failed', rateLimited: true }));
    expect(cooldown.remainingMs('pet-api')).toBe(60_000);
  });

  it('does not assume auth, network, or user cancellation failures are 429s', async () => {
    const cooldown = new ProviderRequestCooldown(() => 0);
    const request = vi.fn(async () => ({ status: 'failed' as const }));
    await cooldown.run('pet-api', request);
    await cooldown.run('pet-api', request);
    expect(request).toHaveBeenCalledTimes(2);
    expect(cooldown.remainingMs('pet-api')).toBe(0);
  });

  it('does not clear a newer 429 when an older concurrent request succeeds', async () => {
    const cooldown = new ProviderRequestCooldown(() => 0);
    let finish!: (value: { status: 'completed' }) => void;
    const older = cooldown.run('pet-api', () => new Promise<{ status: 'completed' }>((resolve) => { finish = resolve; }));
    await cooldown.run('pet-api', async () => ({ status: 'failed', rateLimited: true }));
    finish({ status: 'completed' });
    await older;
    expect(cooldown.remainingMs('pet-api')).toBe(60_000);
  });

  it('caps local backoff and never retries by itself when the waiting time expires', async () => {
    let now = 0;
    const cooldown = new ProviderRequestCooldown(() => now);
    const request = vi.fn(async () => ({ status: 'failed' as const, rateLimited: true }));
    for (let i = 0; i < 8; i++) {
      await cooldown.run('pet-api', request);
      const delay = cooldown.remainingMs('pet-api');
      expect(delay).toBeLessThanOrEqual(300_000);
      now += delay;
    }
    now += 60_000;
    expect(request).toHaveBeenCalledTimes(8);
  });
});

type ReplyOutcome = { status: 'completed' | 'failed' | 'aborted'; rateLimited?: boolean; retryAfterMs?: number };
type CooldownEntry = { until: number; failures: number; lastFailure: number };

const INITIAL_DELAY_MS = 60_000;
const MAX_DELAY_MS = 5 * 60_000;
const FORGET_AFTER_MS = 15 * 60_000;

/** Client-side backoff, not a prediction of the provider's quota reset time. */
export class ProviderRequestCooldown {
  private entries = new Map<string, CooldownEntry>();

  constructor(private now: () => number = () => Date.now()) {}

  remainingMs(providerId: string) {
    const entry = this.entries.get(providerId);
    if (!entry) return 0;
    if (this.now() - entry.lastFailure >= FORGET_AFTER_MS) {
      this.entries.delete(providerId);
      return 0;
    }
    return Math.max(0, entry.until - this.now());
  }

  async run<T extends ReplyOutcome>(
    providerId: string,
    request: () => Promise<T>,
    onBlocked?: (remainingMs: number) => void
  ): Promise<T | ReplyOutcome> {
    const remaining = this.remainingMs(providerId);
    if (remaining > 0) {
      onBlocked?.(remaining);
      return { status: 'failed', rateLimited: true, retryAfterMs: remaining };
    }
    const previous = this.entries.get(providerId);
    const result = await request();
    if (result?.rateLimited) {
      const now = this.now();
      const failures = (this.entries.get(providerId)?.failures ?? 0) + 1;
      this.entries.set(providerId, {
        until: now + Math.min(MAX_DELAY_MS, INITIAL_DELAY_MS * 2 ** Math.min(failures - 1, 3)),
        failures,
        lastFailure: now
      });
      if (this.entries.size > 64) this.entries.delete(this.entries.keys().next().value!);
    } else if (result?.status === 'completed' && this.entries.get(providerId) === previous) {
      // A success already in flight must not clear a newer 429 from another request.
      this.entries.delete(providerId);
    }
    return result;
  }
}

export const providerRequestCooldown = new ProviderRequestCooldown();

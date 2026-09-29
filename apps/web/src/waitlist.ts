/**
 * Client side of `POST /v1/waitlist` (see docs/api.md). The endpoint is idempotent, so submitting the same
 * address twice is harmless and reported as a success.
 */

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Pragmatic shape check; the server remains the authority on what it accepts. */
export function isValidEmail(email: string): boolean {
  return email.length <= 254 && EMAIL_PATTERN.test(email);
}

export type WaitlistResult =
  { ok: true } | { ok: false; reason: 'validation' | 'rate_limited' | 'network' | 'server' };

function apiBase(): string {
  return (import.meta.env.VITE_API_URL ?? '').replace(/\/+$/, '');
}

export async function joinWaitlist(email: string, source = 'landing'): Promise<WaitlistResult> {
  let response: Response;
  try {
    response = await fetch(`${apiBase()}/v1/waitlist`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, source }),
    });
  } catch {
    return { ok: false, reason: 'network' };
  }
  if (response.ok) return { ok: true };
  if (response.status === 400) return { ok: false, reason: 'validation' };
  if (response.status === 429) return { ok: false, reason: 'rate_limited' };
  return { ok: false, reason: 'server' };
}

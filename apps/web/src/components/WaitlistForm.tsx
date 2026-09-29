import { useId, useState, type FormEvent } from 'react';

import { isValidEmail, joinWaitlist } from '../waitlist.ts';

type State =
  { status: 'idle' } | { status: 'loading' } | { status: 'success' } | { status: 'error'; message: string };

const ERROR_MESSAGES = {
  validation: 'That email address does not look right.',
  rate_limited: 'Too many attempts. Please try again in a few minutes.',
  network: 'Could not reach the server. Check your connection and try again.',
  server: 'Something went wrong on our side. Please try again later.',
} as const;

/** Email waitlist signup with client-side validation and explicit loading, success and error states. */
export function WaitlistForm() {
  const [email, setEmail] = useState('');
  const [state, setState] = useState<State>({ status: 'idle' });
  const inputId = useId();
  const messageId = useId();

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (state.status === 'loading') return;
    const trimmed = email.trim();
    if (!isValidEmail(trimmed)) {
      setState({ status: 'error', message: ERROR_MESSAGES.validation });
      return;
    }
    setState({ status: 'loading' });
    const result = await joinWaitlist(trimmed);
    setState(result.ok ? { status: 'success' } : { status: 'error', message: ERROR_MESSAGES[result.reason] });
  }

  if (state.status === 'success') {
    return (
      <p className="waitlist-success" role="status">
        You are on the list. We will email you when there is news.
      </p>
    );
  }

  return (
    <form className="waitlist" onSubmit={(e) => void onSubmit(e)} noValidate>
      <label htmlFor={inputId}>Join the waitlist for updates</label>
      <div className="waitlist-row">
        <input
          id={inputId}
          type="email"
          name="email"
          autoComplete="email"
          placeholder="you@example.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          aria-invalid={state.status === 'error'}
          aria-describedby={messageId}
        />
        <button className="button button-dark" type="submit" disabled={state.status === 'loading'}>
          {state.status === 'loading' ? 'Joining...' : 'Join waitlist'}
        </button>
      </div>
      <p id={messageId} className="waitlist-error" role="alert">
        {state.status === 'error' ? state.message : ''}
      </p>
    </form>
  );
}

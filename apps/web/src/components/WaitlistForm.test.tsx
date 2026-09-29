import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { WaitlistForm } from './WaitlistForm.tsx';

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function submit(email: string) {
  fireEvent.change(screen.getByLabelText(/waitlist/i), { target: { value: email } });
  fireEvent.click(screen.getByRole('button', { name: 'Join waitlist' }));
}

describe('WaitlistForm', () => {
  it('rejects an invalid email without calling the API', async () => {
    render(<WaitlistForm />);

    submit('not-an-email');

    expect(screen.getByRole('alert')).toHaveTextContent(/does not look right/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('posts the trimmed email and shows the success message', async () => {
    fetchMock.mockResolvedValue(new Response('{}', { status: 202 }));
    render(<WaitlistForm />);

    submit(' fan@example.com ');

    expect(await screen.findByRole('status')).toHaveTextContent(/on the list/);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toMatch(/\/v1\/waitlist$/);
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ email: 'fan@example.com', source: 'landing' });
  });

  it('disables the button while the request is in flight', async () => {
    let resolve: (r: Response) => void = () => {};
    fetchMock.mockReturnValue(new Promise<Response>((r) => (resolve = r)));
    render(<WaitlistForm />);

    submit('fan@example.com');

    expect(screen.getByRole('button', { name: 'Joining...' })).toBeDisabled();
    resolve(new Response('{}', { status: 202 }));
    await screen.findByRole('status');
  });

  it.each([
    [429, /too many attempts/i],
    [500, /went wrong/i],
    [400, /does not look right/i],
  ])('shows an error for HTTP %i and lets the visitor retry', async (status, message) => {
    fetchMock.mockResolvedValue(new Response('{}', { status }));
    render(<WaitlistForm />);

    submit('fan@example.com');

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(message));
    expect(screen.getByRole('button', { name: 'Join waitlist' })).toBeEnabled();
  });

  it('shows a connection error when the request cannot be sent', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    render(<WaitlistForm />);

    submit('fan@example.com');

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/could not reach/i));
  });
});

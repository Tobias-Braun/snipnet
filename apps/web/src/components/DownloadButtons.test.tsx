import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { RELEASES_URL } from '../content.ts';
import { DownloadButtons } from './DownloadButtons.tsx';

const RELEASE = {
  tag_name: 'v1.2.0',
  assets: [
    { name: 'snipnet-1.2.0.dmg', browser_download_url: 'https://dl/mac.dmg' },
    { name: 'snipnet-1.2.0.msi', browser_download_url: 'https://dl/win.msi' },
    { name: 'checksums.txt', browser_download_url: 'https://dl/checksums.txt' },
  ],
};

const fetchMock = vi.fn();

function setPlatform(platform: string, userAgent: string) {
  vi.spyOn(navigator, 'platform', 'get').mockReturnValue(platform);
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(userAgent);
}

/**
 * Waits until the mocked fetch has been called and its response has been fully
 * processed, then flushes the resulting React state update. Asserting right
 * after the call alone would check the initial fallback render, which would
 * pass even if the component wrongly rendered an OS button after a failure.
 */
async function settleFetch() {
  await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('DownloadButtons', () => {
  it('links the installer matching the visitor OS and lists the others', async () => {
    setPlatform('MacIntel', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)');
    fetchMock.mockResolvedValue(new Response(JSON.stringify(RELEASE), { status: 200 }));
    render(<DownloadButtons />);

    const primary = await screen.findByRole('link', { name: 'Download for macOS' });
    expect(primary).toHaveAttribute('href', 'https://dl/mac.dmg');
    expect(screen.getByRole('link', { name: 'Windows' })).toHaveAttribute('href', 'https://dl/win.msi');
    expect(screen.queryByRole('link', { name: 'Linux' })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      'https://api.github.com/repos/Tobias-Braun/snipnet/releases/latest',
    );
  });

  it('keeps the releases page link when the visitor OS has no matching asset', async () => {
    setPlatform('Linux x86_64', 'Mozilla/5.0 (X11; Linux x86_64)');
    fetchMock.mockResolvedValue(new Response(JSON.stringify(RELEASE), { status: 200 }));
    render(<DownloadButtons />);

    await screen.findByRole('link', { name: 'macOS' });
    expect(screen.getByRole('link', { name: 'Download the latest release' })).toHaveAttribute(
      'href',
      RELEASES_URL,
    );
  });

  it.each([
    ['a rate limited response', () => new Response('{}', { status: 403 })],
    ['a malformed body', () => new Response('{"nope":true}', { status: 200 })],
  ])('falls back to the releases page on %s', async (_name, respond) => {
    setPlatform('MacIntel', 'Mozilla/5.0 (Macintosh)');
    fetchMock.mockResolvedValue(respond());
    render(<DownloadButtons />);

    await settleFetch();
    expect(screen.getByRole('link', { name: 'Download the latest release' })).toHaveAttribute(
      'href',
      RELEASES_URL,
    );
    expect(screen.queryByRole('link', { name: 'Download for macOS' })).not.toBeInTheDocument();
  });

  it('falls back to the releases page when the request fails', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    render(<DownloadButtons />);

    await settleFetch();
    expect(screen.getByRole('link', { name: 'Download the latest release' })).toHaveAttribute(
      'href',
      RELEASES_URL,
    );
    expect(screen.queryByRole('link', { name: 'macOS' })).not.toBeInTheDocument();
  });
});

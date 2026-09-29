import { afterEach, describe, expect, it, vi } from 'vitest';

import { detectOs, fetchLatestRelease, pickAsset } from './releases.ts';

describe('detectOs', () => {
  it.each([
    ['MacIntel', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 'mac'],
    ['Win32', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', 'windows'],
    ['Linux x86_64', 'Mozilla/5.0 (X11; Linux x86_64)', 'linux'],
    ['iPhone', 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)', 'unknown'],
    ['Linux armv8l', 'Mozilla/5.0 (Linux; Android 14)', 'unknown'],
  ])('maps %s to %s', (platform, userAgent, expected) => {
    expect(detectOs({ platform, userAgent })).toBe(expected);
  });
});

describe('pickAsset', () => {
  const assets = [
    { name: 'Snipnet-1.0.0.AppImage', browser_download_url: 'a' },
    { name: 'Snipnet-1.0.0-arm64.dmg', browser_download_url: 'b' },
    { name: 'Snipnet-Setup-1.0.0.exe', browser_download_url: 'c' },
    { name: 'SHA256SUMS', browser_download_url: 'd' },
  ];

  it('picks the installer by extension per platform', () => {
    expect(pickAsset(assets, 'linux')?.browser_download_url).toBe('a');
    expect(pickAsset(assets, 'mac')?.browser_download_url).toBe('b');
    expect(pickAsset(assets, 'windows')?.browser_download_url).toBe('c');
  });

  it('does not mistake a darwin archive for a Windows build', () => {
    const archives = [
      { name: 'snipnet-darwin-arm64.zip', browser_download_url: 'mac' },
      { name: 'snipnet-windows-x64.zip', browser_download_url: 'win' },
    ];
    expect(pickAsset(archives, 'windows')?.browser_download_url).toBe('win');
    expect(pickAsset(archives, 'mac')?.browser_download_url).toBe('mac');
    expect(pickAsset([archives[0]!], 'windows')).toBeUndefined();
  });

  it('returns undefined when the release has no matching file', () => {
    expect(pickAsset([{ name: 'SHA256SUMS', browser_download_url: 'd' }], 'mac')).toBeUndefined();
  });
});

describe('fetchLatestRelease', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('maps the release assets to one download option per platform', async () => {
    const release = {
      tag_name: 'v2.0.0',
      assets: [
        { name: 'snipnet-2.0.0.deb', browser_download_url: 'https://dl/linux.deb' },
        { name: 'snipnet-2.0.0.dmg', browser_download_url: 'https://dl/mac.dmg' },
        { name: 'broken' },
      ],
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(release))));

    await expect(fetchLatestRelease()).resolves.toEqual({
      version: 'v2.0.0',
      options: [
        { os: 'mac', label: 'macOS', url: 'https://dl/mac.dmg' },
        { os: 'linux', label: 'Linux', url: 'https://dl/linux.deb' },
      ],
    });
  });

  // The component keeps the releases page link whenever this resolves to null, so each failure mode is pinned here.
  it.each([
    ['a rate limited response', () => Promise.resolve(new Response('{}', { status: 403 }))],
    ['a body without assets', () => Promise.resolve(new Response('{"tag_name":"v1"}'))],
    ['a non JSON body', () => Promise.resolve(new Response('<html>'))],
    ['a network error', () => Promise.reject(new TypeError('Failed to fetch'))],
  ])('resolves to null on %s', async (_name, respond) => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(respond));

    await expect(fetchLatestRelease()).resolves.toBeNull();
  });
});

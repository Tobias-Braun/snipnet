import { afterEach, describe, expect, it, vi } from 'vitest';

import { detectArch, detectOs, fetchLatestRelease, pickAsset } from './releases.ts';

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

describe('pickAsset by architecture', () => {
  const assets = [
    { name: 'Snipnet-1.0.0-x64.dmg', browser_download_url: 'mac-x64' },
    { name: 'Snipnet-1.0.0-arm64.dmg', browser_download_url: 'mac-arm' },
    { name: 'Snipnet-1.0.0-amd64.deb', browser_download_url: 'linux-x64' },
    { name: 'Snipnet-1.0.0-aarch64.AppImage', browser_download_url: 'linux-arm' },
    { name: 'Snipnet-Setup-1.0.0.exe', browser_download_url: 'win' },
  ];

  it('prefers the asset carrying the matching arch marker', () => {
    expect(pickAsset(assets, 'mac', 'arm64')?.browser_download_url).toBe('mac-arm');
    expect(pickAsset(assets, 'mac', 'x64')?.browser_download_url).toBe('mac-x64');
    expect(pickAsset(assets, 'linux', 'x64')?.browser_download_url).toBe('linux-x64');
    expect(pickAsset(assets, 'linux', 'arm64')?.browser_download_url).toBe('linux-arm');
  });

  it('accepts x86_64 as an x64 marker', () => {
    const list = [
      { name: 'snipnet-aarch64.deb', browser_download_url: 'arm' },
      { name: 'snipnet-x86_64.rpm', browser_download_url: 'x64' },
    ];
    expect(pickAsset(list, 'linux', 'x64')?.browser_download_url).toBe('x64');
  });

  it('prefers an unmarked universal build over a wrong-arch one', () => {
    const list = [
      { name: 'snipnet-arm64.dmg', browser_download_url: 'arm' },
      { name: 'snipnet-universal.dmg', browser_download_url: 'universal' },
    ];
    expect(pickAsset(list, 'mac', 'x64')?.browser_download_url).toBe('universal');
  });

  it('falls back to the first OS match when only the other arch exists or no arch is given', () => {
    const list = [{ name: 'snipnet-arm64.dmg', browser_download_url: 'arm' }];
    expect(pickAsset(list, 'mac', 'x64')?.browser_download_url).toBe('arm');
    expect(pickAsset(assets, 'mac')?.browser_download_url).toBe('mac-x64');
  });
});

describe('detectArch', () => {
  const chrome = (architecture: string) => ({
    platform: 'MacIntel',
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
    userAgentData: { getHighEntropyValues: () => Promise.resolve({ architecture }) },
  });

  it('uses the client hint when available', async () => {
    await expect(detectArch(chrome('arm'))).resolves.toBe('arm64');
    await expect(detectArch(chrome('x86'))).resolves.toBe('x64');
  });

  it('defaults to arm64 on macOS and x64 elsewhere without a hint', async () => {
    const mac = { platform: 'MacIntel', userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)' };
    const win = { platform: 'Win32', userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' };
    await expect(detectArch(mac)).resolves.toBe('arm64');
    await expect(detectArch(win)).resolves.toBe('x64');
  });

  it('reads arm from the user agent and survives a rejected hint request', async () => {
    const nav = {
      platform: 'Linux aarch64',
      userAgent: 'Mozilla/5.0 (X11; Linux aarch64)',
      userAgentData: { getHighEntropyValues: () => Promise.reject(new Error('blocked')) },
    };
    await expect(detectArch(nav)).resolves.toBe('arm64');
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

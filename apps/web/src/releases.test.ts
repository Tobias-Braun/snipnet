import { describe, expect, it } from 'vitest';

import { detectOs, pickAsset } from './releases.ts';

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

  it('returns undefined when the release has no matching file', () => {
    expect(pickAsset([{ name: 'SHA256SUMS', browser_download_url: 'd' }], 'mac')).toBeUndefined();
  });
});

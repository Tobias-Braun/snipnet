/**
 * Looks up the latest GitHub Release at runtime so the landing page never needs a redeploy to point at a
 * new build, and picks the installer that matches the visitor's operating system.
 */

const LATEST_RELEASE_API = 'https://api.github.com/repos/Tobias-Braun/snipnet/releases/latest';

export type DesktopOs = 'mac' | 'windows' | 'linux';
export type Os = DesktopOs | 'unknown';

export interface ReleaseAsset {
  name: string;
  browser_download_url: string;
}

export interface DownloadOption {
  os: DesktopOs;
  label: string;
  url: string;
}

const OS_LABELS: Record<DesktopOs, string> = {
  mac: 'macOS',
  windows: 'Windows',
  linux: 'Linux',
};

/** File name patterns of the installers each platform's build is expected to publish. */
const ASSET_PATTERNS: Record<DesktopOs, RegExp> = {
  mac: /\.(dmg|pkg)$|(mac|macos|osx|darwin).*\.(zip|tar\.gz)$/i,
  windows: /\.(msi|exe)$|(win|windows).*\.zip$/i,
  linux: /\.(appimage|deb|rpm)$|linux.*\.(tar\.gz|zip)$/i,
};

export function detectOs(nav: Pick<Navigator, 'userAgent' | 'platform'> = navigator): Os {
  const haystack = `${nav.platform} ${nav.userAgent}`;
  // iPhones and iPads report "like Mac OS X" but cannot run the desktop app.
  if (/iphone|ipad|ipod|android/i.test(haystack)) return 'unknown';
  if (/mac/i.test(haystack)) return 'mac';
  if (/win/i.test(haystack)) return 'windows';
  if (/linux|x11|cros/i.test(haystack)) return 'linux';
  return 'unknown';
}

export function pickAsset(assets: readonly ReleaseAsset[], os: DesktopOs): ReleaseAsset | undefined {
  return assets.find((asset) => ASSET_PATTERNS[os].test(asset.name));
}

export interface LatestRelease {
  version: string;
  options: DownloadOption[];
}

/** Resolves to `null` for any failure so callers can fall back to the releases page. */
export async function fetchLatestRelease(signal?: AbortSignal): Promise<LatestRelease | null> {
  try {
    const response = await fetch(LATEST_RELEASE_API, {
      headers: { Accept: 'application/vnd.github+json' },
      signal,
    });
    if (!response.ok) return null;
    const body = (await response.json()) as { tag_name?: unknown; assets?: unknown };
    if (typeof body.tag_name !== 'string' || !Array.isArray(body.assets)) return null;
    const assets = (body.assets as Partial<ReleaseAsset>[]).filter(
      (a): a is ReleaseAsset => typeof a?.name === 'string' && typeof a.browser_download_url === 'string',
    );
    const options: DownloadOption[] = [];
    for (const os of Object.keys(OS_LABELS) as DesktopOs[]) {
      const asset = pickAsset(assets, os);
      if (asset) options.push({ os, label: OS_LABELS[os], url: asset.browser_download_url });
    }
    return { version: body.tag_name, options };
  } catch {
    return null;
  }
}

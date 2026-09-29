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

/**
 * File name patterns of the installers each platform's build is expected to publish. The OS markers in the
 * archive alternatives must stand alone, otherwise "darwin" would contain "win" and a macOS zip would be
 * offered to Windows visitors.
 */
const ASSET_PATTERNS: Record<DesktopOs, RegExp> = {
  mac: /\.(dmg|pkg)$|(^|[^a-z])(mac|macos|osx|darwin)([^a-z]|$).*\.(zip|tar\.gz)$/i,
  windows: /\.(msi|exe)$|(^|[^a-z])(win|win32|win64|windows)([^a-z0-9]|$).*\.zip$/i,
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

export type Arch = 'arm64' | 'x64';

/** Arch markers as they appear in installer names; they must stand alone so "x64" does not match inside other words. */
const ARCH_MARKERS: Record<Arch, RegExp> = {
  arm64: /(^|[^a-z0-9])(arm64|aarch64)([^a-z0-9]|$)/i,
  x64: /(^|[^a-z0-9])(x64|x86[_-]64|amd64)([^a-z0-9]|$)/i,
};

interface ArchNavigator {
  userAgent: string;
  platform: string;
  userAgentData?: {
    getHighEntropyValues?: (hints: string[]) => Promise<{ architecture?: string }>;
  };
}

/**
 * Detects the visitor's CPU architecture. Chromium exposes it through User-Agent Client Hints; Firefox and
 * Safari do not, and Safari reports "Intel" even on Apple Silicon, so without a hint the result is only a
 * default: arm64 when the user agent says so or on macOS (nearly all current Macs), x64 elsewhere.
 */
export async function detectArch(nav: ArchNavigator = navigator, os: Os = detectOs(nav)): Promise<Arch> {
  try {
    const values = await nav.userAgentData?.getHighEntropyValues?.(['architecture']);
    if (values?.architecture === 'arm') return 'arm64';
    if (values?.architecture === 'x86') return 'x64';
  } catch {
    // The hint request can be rejected by permissions policy; fall through to the heuristics.
  }
  if (/arm64|aarch64|armv8/i.test(`${nav.platform} ${nav.userAgent}`)) return 'arm64';
  return os === 'mac' ? 'arm64' : 'x64';
}

/**
 * Picks the installer for an OS. Assets naming the visitor's architecture win, then assets without any arch
 * marker (universal builds), then whatever else matches the OS, which keeps the pre-arch first-match behaviour.
 */
export function pickAsset(
  assets: readonly ReleaseAsset[],
  os: DesktopOs,
  arch?: Arch,
): ReleaseAsset | undefined {
  const candidates = assets.filter((asset) => ASSET_PATTERNS[os].test(asset.name));
  if (!arch) return candidates[0];
  const other: Arch = arch === 'arm64' ? 'x64' : 'arm64';
  return (
    candidates.find((asset) => ARCH_MARKERS[arch].test(asset.name)) ??
    candidates.find((asset) => !ARCH_MARKERS[other].test(asset.name)) ??
    candidates[0]
  );
}

export interface LatestRelease {
  version: string;
  options: DownloadOption[];
}

/** Resolves to `null` for any failure so callers can fall back to the releases page. */
export async function fetchLatestRelease(signal?: AbortSignal, arch?: Arch): Promise<LatestRelease | null> {
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
      const asset = pickAsset(assets, os, arch);
      if (asset) options.push({ os, label: OS_LABELS[os], url: asset.browser_download_url });
    }
    return { version: body.tag_name, options };
  } catch {
    return null;
  }
}

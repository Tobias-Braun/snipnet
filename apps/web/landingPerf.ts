import type { Plugin } from 'vite';

/** The subset of a Rollup output chunk or asset that the HTML rewrite needs. */
export interface BundleEntry {
  readonly type: 'asset' | 'chunk';
  readonly fileName: string;
  readonly source?: string | Uint8Array;
}

/** Only the latin subsets are preloaded. The other subsets are fetched on demand through `unicode-range`. */
const PRELOADED_FONT = /(?:bricolage-grotesque|inter)-latin-wght-normal-.+\.woff2$/;

const STYLESHEET_LINK = /[ \t]*<link rel="stylesheet"[^>]*href="([^"]+)"[^>]*>\n?/g;

/**
 * Removes the render-blocking stylesheet request and preloads the fonts the first paint needs.
 *
 * The stylesheet is small (its size is dominated by `@font-face` rules), so inlining it saves a request
 * round trip before first paint. The latin font files are preloaded because the browser would otherwise only
 * discover them after parsing the CSS. Font preloads need `crossorigin` even for same-origin files, or the
 * browser fetches them twice.
 */
export function optimizeHtml(
  html: string,
  bundle: Readonly<Record<string, BundleEntry>>,
  base = '/',
): string {
  const inlined: string[] = [];
  let result = html.replace(STYLESHEET_LINK, (match, href: string) => {
    const entry = Object.values(bundle).find(
      (candidate) =>
        candidate.type === 'asset' &&
        candidate.fileName.endsWith('.css') &&
        href.endsWith(candidate.fileName),
    );
    if (!entry || typeof entry.source !== 'string') return match;
    inlined.push(entry.source);
    return '';
  });

  const preloads = Object.values(bundle)
    .filter((entry) => entry.type === 'asset' && PRELOADED_FONT.test(entry.fileName))
    .map(
      (entry) =>
        `    <link rel="preload" as="font" type="font/woff2" crossorigin href="${base}${entry.fileName}" />`,
    );
  const styles = inlined.map((css) => `    <style>${css.replace(/<\/style/gi, '<\\/style')}</style>`);

  const injection = [...preloads, ...styles].join('\n');
  if (injection) {
    result = result.replace('</head>', `${injection}\n  </head>`);
  }
  return result;
}

/**
 * Applies `optimizeHtml` to the built `index.html` and drops the stylesheet assets that ended up inlined.
 * The dev server is untouched, since there is no bundle to look at.
 *
 * Only stylesheets that `index.html` linked and no longer links are dropped: CSS of lazily loaded chunks is
 * never in the HTML and must stay, because the chunk loader requests it at runtime. With a relative `base`
 * the built CSS refers to fonts relative to its own location under `assets/`, which would break once the
 * rules live in `index.html`, so the rewrite is skipped there.
 */
export function landingPerf(): Plugin {
  let base = '/';
  return {
    name: 'snipnet-landing-perf',
    apply: 'build',
    configResolved(config) {
      base = config.base;
    },
    transformIndexHtml: {
      order: 'post',
      handler(html, context) {
        const bundle = context.bundle as Record<string, BundleEntry> | undefined;
        if (!bundle || !/^(?:\/|https?:\/\/)/.test(base)) return html;
        const optimized = optimizeHtml(html, bundle, base.endsWith('/') ? base : `${base}/`);
        for (const [key, entry] of Object.entries(bundle)) {
          const inlined = html.includes(entry.fileName) && !optimized.includes(entry.fileName);
          if (entry.fileName.endsWith('.css') && inlined) {
            delete bundle[key];
          }
        }
        return optimized;
      },
    },
  };
}

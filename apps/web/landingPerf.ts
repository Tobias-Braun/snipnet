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
export function optimizeHtml(html: string, bundle: Readonly<Record<string, BundleEntry>>): string {
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
        `    <link rel="preload" as="font" type="font/woff2" crossorigin href="/${entry.fileName}" />`,
    );
  const styles = inlined.map((css) => `    <style>${css.replaceAll('</style', '<\\/style')}</style>`);

  const injection = [...preloads, ...styles].join('\n');
  if (injection) {
    result = result.replace('</head>', `${injection}\n  </head>`);
  }
  return result;
}

/**
 * Applies `optimizeHtml` to the built `index.html` and drops the stylesheet assets that ended up inlined.
 * The dev server is untouched, since there is no bundle to look at.
 */
export function landingPerf(): Plugin {
  return {
    name: 'snipnet-landing-perf',
    apply: 'build',
    transformIndexHtml: {
      order: 'post',
      handler(html, context) {
        const bundle = context.bundle as Record<string, BundleEntry> | undefined;
        if (!bundle) return html;
        const optimized = optimizeHtml(html, bundle);
        for (const [key, entry] of Object.entries(bundle)) {
          if (entry.fileName.endsWith('.css') && !optimized.includes(entry.fileName)) {
            delete bundle[key];
          }
        }
        return optimized;
      },
    },
  };
}

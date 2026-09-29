import { describe, expect, it } from 'vitest';

import { optimizeHtml, type BundleEntry } from './landingPerf.ts';

const HTML = `<!doctype html>
<html>
  <head>
    <script type="module" crossorigin src="/assets/index-abc.js"></script>
    <link rel="stylesheet" crossorigin href="/assets/index-abc.css">
  </head>
  <body></body>
</html>`;

const BUNDLE: Record<string, BundleEntry> = {
  'assets/index-abc.js': { type: 'chunk', fileName: 'assets/index-abc.js' },
  'assets/index-abc.css': { type: 'asset', fileName: 'assets/index-abc.css', source: 'body{margin:0}' },
  'assets/inter-latin-wght-normal-x1.woff2': {
    type: 'asset',
    fileName: 'assets/inter-latin-wght-normal-x1.woff2',
  },
  'assets/bricolage-grotesque-latin-wght-normal-x2.woff2': {
    type: 'asset',
    fileName: 'assets/bricolage-grotesque-latin-wght-normal-x2.woff2',
  },
  'assets/inter-latin-ext-wght-normal-x3.woff2': {
    type: 'asset',
    fileName: 'assets/inter-latin-ext-wght-normal-x3.woff2',
  },
};

describe('optimizeHtml', () => {
  it('inlines the stylesheet instead of linking it', () => {
    const html = optimizeHtml(HTML, BUNDLE);

    expect(html).not.toContain('rel="stylesheet"');
    expect(html).toContain('<style>body{margin:0}</style>');
  });

  it('preloads only the latin variable fonts with crossorigin', () => {
    const html = optimizeHtml(HTML, BUNDLE);

    expect(html).toContain(
      'rel="preload" as="font" type="font/woff2" crossorigin href="/assets/inter-latin-wght-normal-x1.woff2"',
    );
    expect(html).toContain('/assets/bricolage-grotesque-latin-wght-normal-x2.woff2');
    expect(html).not.toContain('latin-ext');
  });

  it('keeps the stylesheet link when the asset is unknown', () => {
    const html = optimizeHtml(HTML, {});

    expect(html).toContain('rel="stylesheet"');
  });

  it('cannot be broken out of by a closing style tag in the CSS', () => {
    const css: BundleEntry = {
      type: 'asset',
      fileName: 'assets/index-abc.css',
      source: 'a{content:"</style>"}',
    };

    const html = optimizeHtml(HTML, { ...BUNDLE, 'assets/index-abc.css': css });

    expect(html.match(/<\/style>/g)).toHaveLength(1);
  });
});

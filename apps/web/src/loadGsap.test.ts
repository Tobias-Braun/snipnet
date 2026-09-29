import { afterEach, describe, expect, it, vi } from 'vitest';

import { loadGsap, loadGsapWithScrollTrigger, motionAllowed } from './loadGsap.ts';

function stubMotionPreference(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('no-preference') ? !reduce : reduce,
  }));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('motionAllowed', () => {
  it('is true when the visitor has no reduced-motion preference', () => {
    stubMotionPreference(false);

    expect(motionAllowed()).toBe(true);
  });

  it('is false when the visitor prefers reduced motion', () => {
    stubMotionPreference(true);

    expect(motionAllowed()).toBe(false);
  });
});

describe('loading GSAP', () => {
  it('resolves the core library on demand', async () => {
    const gsap = await loadGsap();

    expect(typeof gsap.timeline).toBe('function');
  });

  it('registers ScrollTrigger once and shares the same load', async () => {
    const first = loadGsapWithScrollTrigger();
    const second = loadGsapWithScrollTrigger();

    expect(second).toBe(first);
    const gsap = await first;
    // The typings do not declare `core.globals`, which is where GSAP lists registered plugins.
    const core = gsap.core as unknown as { globals(): Record<string, unknown> };
    expect(core.globals().ScrollTrigger).toBeDefined();
  });
});

type Gsap = typeof import('gsap').gsap;

/** Media query under which the page animates at all. */
export const MOTION_QUERY = '(prefers-reduced-motion: no-preference)';

/** Whether the visitor allows motion. Without it, GSAP is never downloaded. */
export function motionAllowed(): boolean {
  return window.matchMedia(MOTION_QUERY).matches;
}

/**
 * Loads GSAP on demand so it stays out of the entry bundle: the initial JavaScript only carries React and
 * the page, and visitors who prefer reduced motion never download the animation library.
 */
export async function loadGsap(): Promise<Gsap> {
  const { gsap } = await import('gsap');
  return gsap;
}

let withScrollTrigger: Promise<Gsap> | undefined;

/** Loads GSAP together with the ScrollTrigger plugin, registering the plugin exactly once. */
export function loadGsapWithScrollTrigger(): Promise<Gsap> {
  withScrollTrigger ??= Promise.all([import('gsap'), import('gsap/ScrollTrigger')]).then(([core, plugin]) => {
    core.gsap.registerPlugin(plugin.ScrollTrigger);
    return core.gsap;
  });
  return withScrollTrigger;
}

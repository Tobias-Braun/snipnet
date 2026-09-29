import { useLayoutEffect, type RefObject } from 'react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

gsap.registerPlugin(ScrollTrigger);

/**
 * Wires up the page's entrance and scroll animations below the hero.
 *
 * Elements opt in through data attributes so the components stay free of animation code:
 * - `data-reveal` fades and lifts the element in when it scrolls into view.
 * - `data-reveal-group` does the same for its direct children with a stagger.
 * - `data-draw` draws an SVG path stroke along with the scroll position.
 *
 * Every tween starts from a transformed or transparent state and ends in the element's natural CSS state,
 * so the page is complete without JavaScript and with `prefers-reduced-motion: reduce`, where nothing is
 * registered at all. Only transform, opacity and stroke offsets change, which keeps layout from shifting.
 */
export function useScrollAnimations(scope: RefObject<HTMLElement | null>) {
  useLayoutEffect(() => {
    const element = scope.current;
    if (!element) return;

    const media = gsap.matchMedia();
    media.add('(prefers-reduced-motion: no-preference)', () => {
      const select = gsap.utils.selector(element);

      for (const target of select('[data-reveal]')) {
        gsap.from(target, {
          y: 36,
          opacity: 0,
          duration: 0.8,
          ease: 'power3.out',
          scrollTrigger: { trigger: target, start: 'top 85%', once: true },
        });
      }

      for (const group of select('[data-reveal-group]')) {
        gsap.from(Array.from(group.children), {
          y: 36,
          opacity: 0,
          duration: 0.7,
          ease: 'power3.out',
          stagger: 0.12,
          scrollTrigger: { trigger: group, start: 'top 85%', once: true },
        });
      }

      for (const path of select<SVGGeometryElement>('[data-draw]')) {
        const length = path.getTotalLength();
        gsap.fromTo(
          path,
          { strokeDasharray: length, strokeDashoffset: length },
          {
            strokeDashoffset: 0,
            ease: 'none',
            scrollTrigger: { trigger: path, start: 'top 85%', end: 'bottom 50%', scrub: true },
          },
        );
      }
    });

    return () => {
      media.revert();
    };
  }, [scope]);
}

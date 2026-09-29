import { useLayoutEffect, useRef } from 'react';

import { loadGsap, motionAllowed, MOTION_QUERY } from '../loadGsap.ts';

/**
 * One piece of the recording. `rally` pieces become the kept clips, the others are dead time that the cut
 * drops away. Coordinates are in the SVG's own 960 x 200 viewBox.
 */
interface Piece {
  readonly x: number;
  readonly width: number;
  readonly kind: 'rally' | 'dead';
}

const PIECES: readonly Piece[] = [
  { x: 0, width: 130, kind: 'dead' },
  { x: 130, width: 200, kind: 'rally' },
  { x: 330, width: 110, kind: 'dead' },
  { x: 440, width: 190, kind: 'rally' },
  { x: 630, width: 90, kind: 'dead' },
  { x: 720, width: 240, kind: 'rally' },
];

const STRIP_Y = 60;
const STRIP_HEIGHT = 80;
const FRAME_STEP = 20;
const RAW_COLOR = '#3a3a3f';
const RALLY_COLOR = '#ffd21f';

const CUT_XS = PIECES.slice(1).map((piece) => piece.x);

/**
 * The hero graphic: a raw footage strip that a playhead scans, cuts into rally clips and dead time.
 *
 * The markup describes the finished state (kept clips highlighted, dead time faded and dropped), so that is
 * what visitors see without JavaScript or with reduced motion. The timeline only animates transforms and
 * paint properties of SVG children inside a fixed-size box, so it cannot shift the page layout.
 */
export function FilmstripHero() {
  const root = useRef<SVGSVGElement>(null);

  useLayoutEffect(() => {
    const element = root.current;
    if (!element) return;

    if (!motionAllowed()) return;

    let disposed = false;
    let revert: (() => void) | undefined;

    void loadGsap().then((gsap) => {
      if (disposed) return;
      const media = gsap.matchMedia();
      revert = () => media.revert();
      media.add(MOTION_QUERY, () => {
        const select = gsap.utils.selector(element);
        const rallies = select('.strip-piece.rally');
        const dead = select('.strip-piece.dead');
        const cuts = select('.strip-cut');
        const playhead = select('.strip-playhead');

        const timeline = gsap.timeline({ defaults: { ease: 'power2.out' }, repeat: -1, repeatDelay: 2.4 });
        timeline
          .fromTo(playhead, { x: 0, opacity: 1 }, { x: 960, duration: 2.6, ease: 'none' }, 0.3)
          .fromTo(rallies, { fill: RAW_COLOR }, { fill: RALLY_COLOR, duration: 0.3, stagger: 0.85 }, 0.6)
          .fromTo(
            cuts,
            { scaleY: 0, opacity: 0, transformOrigin: '50% 0%' },
            { scaleY: 1, opacity: 1, duration: 0.3, stagger: 0.12 },
            2.9,
          )
          .to(playhead, { opacity: 0, duration: 0.2 }, 2.9)
          .fromTo(dead, { y: 0, opacity: 1 }, { y: 34, opacity: 0.18, duration: 0.6, stagger: 0.1 }, 3.5)
          .fromTo(rallies, { y: 0 }, { y: -10, duration: 0.5, stagger: 0.1 }, 3.6);

        return () => {
          timeline.kill();
        };
      });
    });

    return () => {
      disposed = true;
      revert?.();
    };
  }, []);

  return (
    <svg
      ref={root}
      className="filmstrip"
      viewBox="0 0 960 200"
      role="img"
      aria-label="A raw footage strip is scanned, then cut into rally clips while the dead time between them falls away."
    >
      <defs>
        <pattern id="frames" width={FRAME_STEP} height={STRIP_HEIGHT} patternUnits="userSpaceOnUse">
          <rect x="0" y="0" width="2" height={STRIP_HEIGHT} fill="#0d0d0f" opacity="0.55" />
        </pattern>
      </defs>
      {PIECES.map((piece) => {
        const isRally = piece.kind === 'rally';
        return (
          <g
            key={piece.x}
            className={`strip-piece ${piece.kind}`}
            fill={isRally ? RALLY_COLOR : RAW_COLOR}
            transform={isRally ? 'translate(0 -10)' : 'translate(0 34)'}
            opacity={isRally ? 1 : 0.18}
          >
            <rect x={piece.x + 3} y={STRIP_Y} width={piece.width - 6} height={STRIP_HEIGHT} rx="6" />
            <rect
              x={piece.x + 3}
              y={STRIP_Y}
              width={piece.width - 6}
              height={STRIP_HEIGHT}
              rx="6"
              fill="url(#frames)"
            />
          </g>
        );
      })}
      {CUT_XS.map((x) => (
        <line key={x} className="strip-cut" x1={x} x2={x} y1="30" y2="170" />
      ))}
      <line className="strip-playhead" x1="0" x2="0" y1="20" y2="180" opacity="0" />
    </svg>
  );
}

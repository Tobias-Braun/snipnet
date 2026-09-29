import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Vitest runs without globals, so Testing Library cannot register its automatic cleanup hook itself.
afterEach(() => {
  cleanup();
});

// jsdom has no matchMedia. Reporting `prefers-reduced-motion: reduce` keeps GSAP from registering
// animations, so tests see the page's final markup without timelines or ScrollTrigger running.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: query.includes(': reduce'),
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }),
});

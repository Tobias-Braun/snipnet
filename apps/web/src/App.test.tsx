import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App.tsx';
import { FEATURES, RELEASES_URL, STEPS } from './content.ts';

describe('App', () => {
  it('renders the product pitch as the single page heading', () => {
    render(<App />);

    const headings = screen.getAllByRole('heading', { level: 1 });
    expect(headings).toHaveLength(1);
    expect(headings[0]).toHaveTextContent('Just the rallies');
  });

  it('renders every landing page section as a labelled region', () => {
    render(<App />);

    for (const name of [
      /Hours of footage in/,
      /four steps/,
      /way roundnet is filmed/,
      /learns from your edits/,
      /Get your weekend back/,
    ]) {
      expect(screen.getByRole('region', { name })).toBeInTheDocument();
    }
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });

  it('lists the four workflow steps in order', () => {
    render(<App />);

    const steps = within(screen.getByRole('region', { name: /four steps/ })).getAllByRole('listitem');
    expect(steps.map((step) => within(step).getByRole('heading').textContent)).toEqual(
      STEPS.map((step) => step.title),
    );
  });

  it('shows every feature in the grid', () => {
    render(<App />);

    const grid = screen.getByRole('region', { name: /way roundnet is filmed/ });
    for (const feature of FEATURES) {
      expect(within(grid).getByRole('heading', { name: feature.title })).toBeInTheDocument();
    }
  });

  it('explains that training on user edits is opt-in', () => {
    render(<App />);

    expect(screen.getByText(/Sharing is opt-in/)).toBeInTheDocument();
  });

  it('links the download call to action to the releases page', () => {
    render(<App />);

    const cta = screen.getByRole('link', { name: 'Download the latest release' });
    expect(cta).toHaveAttribute('href', RELEASES_URL);
  });

  it('offers a skip link and in-page navigation targets that exist', () => {
    const { container } = render(<App />);

    const anchors = container.querySelectorAll<HTMLAnchorElement>('a[href^="#"]');
    expect(anchors.length).toBeGreaterThan(0);
    for (const anchor of anchors) {
      const id = anchor.getAttribute('href')?.slice(1);
      expect(container.querySelector(`#${id}`), `target of ${anchor.textContent}`).not.toBeNull();
    }
  });

  it('gives the hero graphic a text alternative', () => {
    render(<App />);

    expect(screen.getByRole('img', { name: /raw footage strip/ })).toBeInTheDocument();
  });
});

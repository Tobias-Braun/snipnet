import { useRef } from 'react';

import { DownloadButtons } from './components/DownloadButtons.tsx';
import { FilmstripHero } from './components/FilmstripHero.tsx';
import { WaitlistForm } from './components/WaitlistForm.tsx';
import { FEATURES, RELEASES_URL, REPO_URL, STEPS } from './content.ts';
import { useScrollAnimations } from './useScrollAnimations.ts';

/**
 * The Snipnet landing page: hero, how it works, feature grid, the learning-from-edits explainer, the
 * download call to action and the footer. Scroll animations are attached by `useScrollAnimations`.
 */
export function App() {
  const page = useRef<HTMLDivElement>(null);
  useScrollAnimations(page);

  return (
    <div ref={page}>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="site-header">
        <a className="brand" href="#top" aria-label="Snipnet home">
          <span className="brand-mark" aria-hidden="true" />
          Snipnet
        </a>
        <nav aria-label="Primary">
          <ul>
            <li>
              <a href="#how-it-works">How it works</a>
            </li>
            <li>
              <a href="#features">Features</a>
            </li>
            <li>
              <a href="#learning">AI</a>
            </li>
            <li>
              <a className="button button-small" href="#download">
                Download
              </a>
            </li>
          </ul>
        </nav>
      </header>

      <main id="main">
        <section id="top" className="hero net-texture" aria-labelledby="hero-title">
          <div className="container">
            <p className="eyebrow">AI rally cutter for roundnet</p>
            <h1 id="hero-title">
              Hours of footage in. <span className="accent">Just the rallies</span> out.
            </h1>
            <p className="lead">
              Snipnet finds every rally in your roundnet recording, lets you fine-tune the cuts on a timeline
              and exports clips that are ready to share.
            </p>
            <div className="actions">
              <a className="button" href="#download">
                Download Snipnet
              </a>
              <a className="button button-ghost" href="#how-it-works">
                See how it works
              </a>
            </div>
            <FilmstripHero />
          </div>
        </section>

        <section id="how-it-works" className="section" aria-labelledby="how-title">
          <div className="container">
            <h2 id="how-title" data-reveal>
              From raw session to rally clips in four steps
            </h2>
            <ol className="steps" data-reveal-group>
              {STEPS.map((step) => (
                <li key={step.title}>
                  <h3>{step.title}</h3>
                  <p>{step.text}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section id="features" className="section section-alt" aria-labelledby="features-title">
          <div className="container">
            <h2 id="features-title" data-reveal>
              Built for the way roundnet is filmed
            </h2>
            <ul className="feature-grid" data-reveal-group>
              {FEATURES.map((feature) => (
                <li key={feature.title}>
                  <h3>{feature.title}</h3>
                  <p>{feature.text}</p>
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section id="learning" className="section" aria-labelledby="learning-title">
          <div className="container learning">
            <div data-reveal>
              <h2 id="learning-title">An AI that learns from your edits</h2>
              <p>
                Every time you move a cut point, you teach the model what a rally really looks like. If you
                choose to share them, your final cuts help train the next version, so detection gets better
                for everyone.
              </p>
              <p className="consent">
                <strong>Sharing is opt-in.</strong> Training is off until you switch it on, you can turn it
                off again at any time, and without your consent your videos and edits are never used to train
                a model.
              </p>
            </div>
            <svg
              className="loop"
              viewBox="0 0 320 240"
              role="img"
              aria-label="A loop: you edit the cuts, the model learns, the suggestions improve."
            >
              <path
                data-draw
                d="M60 60 C 140 -10, 260 10, 260 80 C 260 150, 200 190, 160 190 C 90 190, 40 150, 60 60"
                fill="none"
                strokeWidth="6"
                strokeLinecap="round"
              />
              <g className="loop-node">
                <circle cx="60" cy="60" r="16" />
                <circle cx="260" cy="80" r="16" />
                <circle cx="160" cy="190" r="16" />
              </g>
              <g className="loop-label">
                <text x="60" y="30" textAnchor="middle">
                  You edit
                </text>
                <text x="260" y="52" textAnchor="middle">
                  Model learns
                </text>
                <text x="160" y="228" textAnchor="middle">
                  Better cuts
                </text>
              </g>
            </svg>
          </div>
        </section>

        <section id="download" className="download" aria-labelledby="download-title">
          <div className="container" data-reveal>
            <h2 id="download-title">Get your weekend back</h2>
            <p>Snipnet runs on your desktop, with new builds published on every release.</p>
            <DownloadButtons />
            <WaitlistForm />
          </div>
        </section>
      </main>

      <footer className="site-footer">
        <div className="container footer-row">
          <p>Snipnet, made for the roundnet community.</p>
          <ul>
            <li>
              <a href={REPO_URL}>Source on GitHub</a>
            </li>
            <li>
              <a href={RELEASES_URL}>Releases</a>
            </li>
            <li>
              <a href="#top">Back to top</a>
            </li>
          </ul>
        </div>
      </footer>
    </div>
  );
}

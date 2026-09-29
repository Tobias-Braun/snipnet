import { useEffect, useState } from 'react';

import { RELEASES_URL } from '../content.ts';
import { detectArch, detectOs, fetchLatestRelease, type DownloadOption, type LatestRelease } from '../releases.ts';

/**
 * Download call to action. It renders the releases page link immediately, then upgrades to a primary
 * button for the visitor's OS (plus links for the other platforms) once the latest release has loaded.
 * Any failure or a release without a matching installer leaves the releases page link in place.
 */
export function DownloadButtons() {
  const [release, setRelease] = useState<LatestRelease | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void detectArch()
      .then((arch) => fetchLatestRelease(controller.signal, arch))
      .then((latest) => {
        if (!controller.signal.aborted) setRelease(latest);
      });
    return () => controller.abort();
  }, []);

  const os = detectOs();
  const primary: DownloadOption | undefined = release?.options.find((option) => option.os === os);
  const others = release?.options.filter((option) => option !== primary) ?? [];

  return (
    <div className="download-buttons">
      {primary ? (
        <a className="button button-dark" href={primary.url}>
          Download for {primary.label}
        </a>
      ) : (
        <a className="button button-dark" href={RELEASES_URL}>
          Download the latest release
        </a>
      )}
      {release && others.length > 0 && (
        <p className="download-other">
          {primary ? 'Also available for ' : 'Available for '}
          {others.map((option, index) => (
            <span key={option.os}>
              {index > 0 && ', '}
              <a href={option.url}>{option.label}</a>
            </span>
          ))}
          . Version {release.version}.
        </p>
      )}
    </div>
  );
}

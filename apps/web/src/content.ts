/**
 * Copy for the landing page, kept apart from the markup so wording changes never touch the components and
 * the tests can assert against the same source of truth.
 */

export const RELEASES_URL = 'https://github.com/Tobias-Braun/snipnet/releases';
export const REPO_URL = 'https://github.com/Tobias-Braun/snipnet';

export interface Copy {
  title: string;
  text: string;
}

export const STEPS: readonly Copy[] = [
  {
    title: 'Import',
    text: 'Drop in a full session recording. Snipnet builds a light proxy in the background so scrubbing stays smooth, while your original file never leaves your disk.',
  },
  {
    title: 'AI finds the rallies',
    text: 'A model watches the ball, the players and the sound of the hit, then proposes a cut for every rally and skips the walking, serving prep and chatting.',
  },
  {
    title: 'Fine-tune on the timeline',
    text: 'Nudge a start, trim an end, split a rally or delete a false positive. Every edit snaps to the frame and is fully undoable.',
  },
  {
    title: 'Export',
    text: 'Render the clips as separate files or one highlight reel, ready for the group chat or your editor.',
  },
];

export const FEATURES: readonly Copy[] = [
  {
    title: 'Rally detection',
    text: 'Finds where each rally starts and ends, so a two-hour session becomes a list of clips in minutes.',
  },
  {
    title: 'Frame-accurate timeline',
    text: 'Move cut points with the keyboard or the mouse and see the result instantly.',
  },
  {
    title: 'Court selection',
    text: 'Mark the net once and the AI focuses on the play area instead of the crowd behind it.',
  },
  {
    title: 'Original quality export',
    text: 'Edits run on a small proxy, the export is cut from your untouched original footage.',
  },
  {
    title: 'Non-destructive',
    text: 'Your source files are never modified. A project is just a list of cut points.',
  },
  {
    title: 'Desktop native',
    text: 'A fast desktop app for your own machine, with review apps for phones on the roadmap.',
  },
];

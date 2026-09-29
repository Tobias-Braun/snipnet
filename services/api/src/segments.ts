import Type, { type Static } from 'typebox';

import { AppError } from './errors.js';

/**
 * One rally segment as accepted in request bodies (the worker's result and a user's corrected set). Times are
 * integer milliseconds; the invariants that involve the video or neighbouring segments are checked by
 * `assertValidSegments`.
 */
export const SegmentInput = Type.Object(
  {
    startMs: Type.Integer({ minimum: 0 }),
    endMs: Type.Integer({ minimum: 1 }),
    label: Type.Literal('rally'),
    // Null comes first on purpose: Fastify's Ajv coerces types, and with the number branch first it would
    // turn a `null` confidence into 0.
    confidence: Type.Optional(Type.Union([Type.Null(), Type.Number({ minimum: 0, maximum: 1 })])),
  },
  { additionalProperties: false },
);

export type SegmentInputType = Static<typeof SegmentInput>;

/**
 * Enforces the segment invariants of docs/api.md: each segment is non-empty and inside the video, and the list
 * is sorted by start with no overlaps (touching segments are allowed). `field` prefixes error messages so
 * callers validating several lists (a set and the snapshots of its edit log) can tell them apart.
 */
export function assertValidSegments(
  segments: readonly Pick<SegmentInputType, 'startMs' | 'endMs'>[],
  durationMs: number,
  field = 'segments',
): void {
  let previousEnd = 0;
  for (const [index, segment] of segments.entries()) {
    const at = `${field}[${String(index)}]`;
    if (segment.startMs >= segment.endMs) {
      throw new AppError('validation_error', `${at}: startMs must be smaller than endMs`);
    }
    if (segment.endMs > durationMs) {
      throw new AppError(
        'validation_error',
        `${at}: endMs exceeds the video duration of ${String(durationMs)} ms`,
      );
    }
    if (segment.startMs < previousEnd) {
      throw new AppError(
        'validation_error',
        `${at}: segments must be sorted by startMs and must not overlap`,
      );
    }
    previousEnd = segment.endMs;
  }
}

/** Serializes segments for the jsonb column, making an absent confidence explicit as `null`. */
export function serializeSegments(segments: readonly SegmentInputType[]): string {
  return JSON.stringify(
    segments.map((s) => ({
      startMs: s.startMs,
      endMs: s.endMs,
      label: 'rally',
      confidence: s.confidence ?? null,
    })),
  );
}

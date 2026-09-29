import Type from 'typebox';

/** Body of every error response, as specified in docs/api.md. */
export const ErrorResponse = Type.Object(
  {
    error: Type.Object({
      code: Type.String({ description: 'Stable snake_case identifier clients can branch on.' }),
      message: Type.String({ description: 'Human readable explanation.' }),
    }),
  },
  { $id: 'ErrorResponse' },
);

const Timestamp = Type.String({ format: 'date-time' });

export const Job = Type.Object(
  {
    id: Type.String({ format: 'uuid' }),
    videoId: Type.String({ format: 'uuid' }),
    status: Type.Union([
      Type.Literal('queued'),
      Type.Literal('running'),
      Type.Literal('succeeded'),
      Type.Literal('failed'),
    ]),
    progress: Type.Number({ minimum: 0, maximum: 1 }),
    modelVersion: Type.Union([Type.String(), Type.Null()]),
    error: Type.Union([Type.String(), Type.Null()]),
    attempts: Type.Integer(),
    createdAt: Timestamp,
    startedAt: Type.Union([Timestamp, Type.Null()]),
    finishedAt: Type.Union([Timestamp, Type.Null()]),
  },
  { $id: 'Job' },
);

/** Normalized coordinate: a fraction of the frame width or height, origin top-left. */
const Unit = Type.Number({ minimum: 0, maximum: 1 });

const courtFields = {
  roi: Type.Object(
    {
      x: Unit,
      y: Unit,
      width: Type.Number({ exclusiveMinimum: 0, maximum: 1 }),
      height: Type.Number({ exclusiveMinimum: 0, maximum: 1 }),
    },
    { additionalProperties: false },
  ),
  netPoint: Type.Object({ x: Unit, y: Unit }, { additionalProperties: false }),
};

/** The court as registered with `$id` for `$ref`s in responses. */
export const Court = Type.Object(courtFields, { $id: 'Court', additionalProperties: false });

/**
 * The same shape without an `$id`, for request bodies: an inline schema that carries an `$id` already registered
 * through `addSchema` would be rejected as a duplicate.
 */
export const CourtBody = Type.Object(courtFields, { additionalProperties: false });

export const Video = Type.Object(
  {
    id: Type.String({ format: 'uuid' }),
    filename: Type.String(),
    durationMs: Type.Integer(),
    width: Type.Integer(),
    height: Type.Integer(),
    fps: Type.Number(),
    proxySizeBytes: Type.Integer(),
    status: Type.Union([
      Type.Literal('created'),
      Type.Literal('uploaded'),
      Type.Literal('analyzing'),
      Type.Literal('analyzed'),
      Type.Literal('failed'),
    ]),
    court: Type.Union([Type.Ref('Court'), Type.Null()]),
    createdAt: Timestamp,
    updatedAt: Timestamp,
    latestJob: Type.Union([Type.Ref('Job'), Type.Null()]),
  },
  { $id: 'Video' },
);

const SegmentOut = Type.Object({
  startMs: Type.Integer(),
  endMs: Type.Integer(),
  label: Type.Literal('rally'),
  confidence: Type.Union([Type.Number(), Type.Null()]),
});

const EditOpOut = Type.Object({
  op: Type.String(),
  atMs: Type.Number(),
  before: Type.Array(SegmentOut),
  after: Type.Array(SegmentOut),
});

export const SegmentSet = Type.Object(
  {
    id: Type.String({ format: 'uuid' }),
    videoId: Type.String({ format: 'uuid' }),
    kind: Type.Union([Type.Literal('prediction'), Type.Literal('user')]),
    parentSetId: Type.Union([Type.String({ format: 'uuid' }), Type.Null()]),
    jobId: Type.Union([Type.String({ format: 'uuid' }), Type.Null()]),
    modelVersion: Type.Union([Type.String(), Type.Null()]),
    segments: Type.Array(SegmentOut),
    scores: Type.Union([Type.Object({ hz: Type.Number(), values: Type.Array(Type.Number()) }), Type.Null()]),
    editLog: Type.Union([Type.Array(EditOpOut), Type.Null()]),
    isFinal: Type.Boolean(),
    createdAt: Timestamp,
  },
  { $id: 'SegmentSet' },
);

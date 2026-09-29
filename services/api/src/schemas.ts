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

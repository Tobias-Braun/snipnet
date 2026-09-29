import type { TypeBoxTypeProvider } from '@fastify/type-provider-typebox';
import type { FastifyPluginCallback } from 'fastify';
import { sql } from 'kysely';
import Type from 'typebox';

export interface WaitlistRouteOptions {
  waitlistRateLimit: { max: number; windowMs: number };
}

/**
 * Public sign-up for the landing page. Emails are stored lowercased and unique case-insensitively (the
 * `waitlist_email_lower_key` index), so repeating a sign-up is a silent no-op that keeps the first `source`.
 * The response is identical for new and known addresses, which avoids revealing who is on the list.
 */
export const waitlistRoutes: FastifyPluginCallback<WaitlistRouteOptions> = (app, options, done) => {
  app.withTypeProvider<TypeBoxTypeProvider>().post(
    '/waitlist',
    {
      config: {
        rateLimit: { max: options.waitlistRateLimit.max, timeWindow: options.waitlistRateLimit.windowMs },
      },
      schema: {
        tags: ['waitlist'],
        body: Type.Object(
          {
            email: Type.String({ format: 'email', maxLength: 254 }),
            source: Type.Optional(Type.String({ minLength: 1, maxLength: 64 })),
          },
          { additionalProperties: false },
        ),
        response: { 202: Type.Object({}), 429: { $ref: 'ErrorResponse#' } },
      },
    },
    async (request, reply) => {
      const { email, source } = request.body;
      await app.db
        .insertInto('waitlist')
        .values({ email: email.toLowerCase(), source: source ?? null })
        .onConflict((conflict) => conflict.expression(sql`lower(email)`).doNothing())
        .execute();
      return reply.code(202).send({});
    },
  );

  done();
};

import { defineConfig } from '@hey-api/openapi-ts'

export default defineConfig({
  input: '../contract/openapi.yaml',
  output: 'src/generated',
  plugins: [
    { name: '@hey-api/typescript', enums: 'javascript' },
    {
      name: 'valibot',
      definitions: true,
      responses: true,
      requests: false,
      webhooks: false,
      $resolvers: {
        // The generated types read int64 as number; without its format the validator does too, instead of a bigint.
        number(ctx) {
          if (ctx.utils.shouldCoerceToBigInt(ctx.schema.format)) {
            ctx.schema = { ...ctx.schema, format: undefined }
          }
          return undefined
        },
      },
    },
  ],
})

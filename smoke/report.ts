import type { Reply } from './client.ts'

export class SmokeFailure extends Error {}

export function fail(message: string): never {
  throw new SmokeFailure(message)
}

export function pass(message: string): void {
  console.log(`ok: ${message}`)
}

function describeError(error: unknown): string {
  if (!(error instanceof Error)) return String(error)
  return error.cause instanceof Error ? `${error.message} (${error.cause.message})` : error.message
}

export function failureMessage(error: unknown): string {
  if (error instanceof SmokeFailure) return error.message
  return `unexpected error: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`
}

// A transfer can break after the status line, so a request that rejects is a failed step, not a crash.
export async function checkStatus(step: string, expected: number | readonly number[], pending: Promise<Reply>): Promise<Reply> {
  const accepted = typeof expected === 'number' ? [expected] : expected
  let reply: Reply
  try {
    reply = await pending
  } catch (error) {
    return fail(`${step}: no answer: ${describeError(error)}`)
  }
  if (!accepted.includes(reply.status)) {
    fail(`${step}: expected HTTP ${accepted.join(' or ')}, got ${reply.status}`)
  }
  return reply
}

export async function expectStatus(step: string, expected: number | readonly number[], pending: Promise<Reply>): Promise<Reply> {
  const reply = await checkStatus(step, expected, pending)
  pass(step)
  return reply
}

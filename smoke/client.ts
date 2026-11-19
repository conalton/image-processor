import { lookup } from 'node:dns'
import { Agent, fetch } from 'undici'
import type { Headers, RequestInit } from 'undici'

const REQUEST_TIMEOUT_MS = 30_000

export interface Reply {
  status: number
  headers: Headers
  body: Buffer
}

export type Send = (url: string, init?: RequestInit) => Promise<Reply>

// With connectHost every connection goes to that host while the URL keeps its own host name, so the Host header, the
// SigV4 signature and CORS see the browser's URL.
export function createSend(connectHost: string | undefined): Send {
  const dispatcher =
    connectHost === undefined
      ? undefined
      : new Agent({ connect: { lookup: (_hostname, options, callback) => lookup(connectHost, options, callback) } })
  return async (url, init) => {
    const response = await fetch(url, {
      redirect: 'manual',
      ...init,
      dispatcher,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
    return { status: response.status, headers: response.headers, body: Buffer.from(await response.arrayBuffer()) }
  }
}

export function bodyText(reply: Reply): string {
  return reply.body.toString('utf8')
}

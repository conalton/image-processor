import type { Config } from './config.ts'
import { bodyText, type Reply, type Send } from './client.ts'
import { checkStatus, expectStatus, fail } from './report.ts'

export interface Upload {
  url: string
  headers: Record<string, string>
}

export interface Link {
  viewUrl: string
  downloadUrl: string
}

export interface Variant extends Link {
  name: string
  contentType: string
}

export interface Task {
  status: string
  errorCode: string
  original: Link | undefined
  variants: Variant[]
}

export interface Fixture {
  name: string
  bytes: Buffer
}

export type UploadHeaders = 'signed' | 'without-length' | 'wrong-type' | 'wrong-disposition'

const TERMINAL_STATES = ['DONE', 'INPUT_REJECTED', 'FAILED']
const POLL_ATTEMPTS = 60
const POLL_DELAY_MS = 2000

function record(value: unknown, what: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) fail(`${what} is not an object`)
  return value as Record<string, unknown>
}

function text(parent: Record<string, unknown>, key: string, what: string): string {
  const value = parent[key]
  if (typeof value !== 'string') fail(`${what}.${key} is not a string`)
  return value
}

function parseJson(body: string, what: string): unknown {
  try {
    return JSON.parse(body)
  } catch {
    return fail(`${what} is not JSON`)
  }
}

export function parseRecord(body: string, what: string): Record<string, unknown> {
  return record(parseJson(body, what), what)
}

function parseLink(value: unknown, what: string): Link {
  const link = record(value, what)
  return { viewUrl: text(link, 'viewUrl', what), downloadUrl: text(link, 'downloadUrl', what) }
}

function parseVariant(value: unknown): Variant {
  const variant = record(value, 'variant')
  return { ...parseLink(variant, 'variant'), name: text(variant, 'name', 'variant'), contentType: text(variant, 'contentType', 'variant') }
}

function parseTask(body: string): Task {
  const task = parseRecord(body, 'task')
  const error = task.error === undefined || task.error === null ? undefined : record(task.error, 'task.error')
  return {
    status: text(task, 'status', 'task'),
    errorCode: error === undefined ? 'none' : text(error, 'code', 'task.error'),
    original: task.original === undefined ? undefined : parseLink(task.original, 'task.original'),
    variants: Array.isArray(task.variants) ? task.variants.map(parseVariant) : [],
  }
}

export async function createTask(send: Send, config: Config, fixture: Fixture, contentType: string): Promise<{ id: string; upload: Upload }> {
  const request = { fileName: fixture.name, contentType, size: fixture.bytes.length }
  // LocalStack answers 403 to an Origin missing from EXTRA_CORS_ALLOWED_ORIGINS (compose.yaml).
  const reply = await expectStatus(
    `create task for ${fixture.name} as ${contentType}`,
    201,
    send(`${config.api}/api/tasks`, {
      method: 'POST',
      headers: { origin: config.pageOrigin, 'content-type': 'application/json' },
      body: JSON.stringify(request),
    }),
  )
  const created = parseRecord(bodyText(reply), 'create response')
  const upload = record(created.upload, 'create response.upload')
  const headers = record(upload.headers, 'create response.upload.headers')
  return {
    id: text(created, 'id', 'create response'),
    upload: {
      url: text(upload, 'url', 'create response.upload'),
      headers: Object.fromEntries(Object.keys(headers).map((name) => [name, text(headers, name, 'create response.upload.headers')])),
    },
  }
}

export function putOriginal(send: Send, upload: Upload, bytes: Buffer, headerCase: UploadHeaders): Promise<Reply> {
  const headers = { ...upload.headers }
  if (headerCase === 'without-length') delete headers['content-length']
  if (headerCase === 'wrong-type') headers['content-type'] = 'image/jpeg'
  if (headerCase === 'wrong-disposition') headers['content-disposition'] = 'attachment; filename=x'
  return send(upload.url, { method: 'PUT', headers, body: bytes })
}

export async function waitForTerminalState(send: Send, config: Config, id: string): Promise<Task> {
  let task: Task | undefined
  for (let attempt = 1; attempt <= POLL_ATTEMPTS; attempt++) {
    const reply = await checkStatus(`read task, attempt ${attempt}`, 200, send(`${config.api}/api/tasks/${id}`))
    task = parseTask(bodyText(reply))
    if (TERMINAL_STATES.includes(task.status)) return task
    await new Promise((resolve) => setTimeout(resolve, POLL_DELAY_MS))
  }
  return fail(`task ${id} is still ${task?.status} after ${(POLL_ATTEMPTS * POLL_DELAY_MS) / 1000} s`)
}

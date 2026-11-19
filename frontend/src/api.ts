import * as v from 'valibot'
import type {
  ApiError,
  CreateTaskData,
  CreateTaskRequest,
  CreateTaskResponse,
  GetTaskData,
  Task,
  TaskId,
  UploadInstructions,
} from './generated'
import { vApiError, vCreateTaskResponse, vTask } from './generated/valibot.gen'

export type ApiFailure =
  | { kind: 'http-error'; status: number; error: ApiError | null }
  | { kind: 'network-error' }

// Terraform writes config.json and the spec does not describe it. retryBudgetSeconds: queue_retry_budget_seconds in
// infra/locals.tf.
const vApiConfig = v.object({
  apiUrl: v.pipe(v.string(), v.url()),
  retryBudgetSeconds: v.pipe(v.number(), v.integer(), v.minValue(1)),
})

export type ApiConfig = v.InferOutput<typeof vApiConfig>

export type ApiResult<T> = { kind: 'ok'; value: T } | ApiFailure

// unconfirmed: an attempt got no answer, a 409 or a 5xx, and the file may be stored; http-error: nothing was stored.
export type UploadResult = { kind: 'stored' } | { kind: 'unconfirmed' } | Extract<ApiFailure, { kind: 'http-error' }>

const MAX_UPLOAD_ATTEMPTS = 3
const UPLOAD_RETRY_DELAY_MS = 2000
const API_REQUEST_TIMEOUT_MS = 30_000
// Per attempt: the largest upload at about 1 Mbit/s.
const UPLOAD_ATTEMPT_TIMEOUT_MS = 180_000

export async function loadApiConfig(): Promise<ApiConfig | null> {
  const response = await send('config.json', { cache: 'no-store' }, API_REQUEST_TIMEOUT_MS)
  if (response === null || !response.ok) {
    return null
  }
  try {
    const config = v.safeParse(vApiConfig, await response.json())
    return config.success ? config.output : null
  } catch {
    return null
  }
}

export async function createTask(
  config: ApiConfig,
  request: CreateTaskRequest,
  cancel: AbortSignal,
): Promise<ApiResult<CreateTaskResponse>> {
  const url: CreateTaskData['url'] = '/api/tasks'
  const response = await send(
    `${config.apiUrl}${url}`,
    { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(request) },
    API_REQUEST_TIMEOUT_MS,
    cancel,
  )
  return readResult(response, vCreateTaskResponse)
}

export async function uploadFile(
  file: File,
  upload: UploadInstructions,
  cancel: AbortSignal,
): Promise<UploadResult> {
  // An earlier attempt may have stored the object: a later refusal then counts as unconfirmed.
  let unconfirmed = false
  for (let attempt = 1; ; attempt++) {
    // `content-length` is among the signed headers; fetch drops it and the browser sets it from the body.
    const response = await send(
      upload.url,
      { method: upload.method, headers: upload.headers, body: file },
      UPLOAD_ATTEMPT_TIMEOUT_MS,
      cancel,
    )
    // 412: an earlier PUT of this task already stored the object.
    if (response !== null && (response.ok || response.status === 412)) {
      return { kind: 'stored' }
    }
    // No answer, 409 or 5xx: retry, safe under the signed If-None-Match. Other refusals (such as 403) store nothing.
    if (response === null || response.status === 409 || response.status >= 500) {
      unconfirmed = true
      if (attempt < MAX_UPLOAD_ATTEMPTS && !cancel.aborted) {
        await sleep(UPLOAD_RETRY_DELAY_MS)
        continue
      }
      return { kind: 'unconfirmed' }
    }
    return unconfirmed ? { kind: 'unconfirmed' } : { kind: 'http-error', status: response.status, error: null }
  }
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export async function getTask(config: ApiConfig, id: TaskId, cancel: AbortSignal): Promise<ApiResult<Task>> {
  const url: GetTaskData['url'] = '/api/tasks/{id}'
  const response = await send(
    `${config.apiUrl}${url.replace('{id}', encodeURIComponent(id))}`,
    { method: 'GET' },
    API_REQUEST_TIMEOUT_MS,
    cancel,
  )
  return readResult(response, vTask)
}

// null: no HTTP answer (network, CORS, timeout or cancel). The signal also aborts the later reading of the body.
async function send(
  url: string,
  init: RequestInit,
  timeoutMs: number,
  cancel?: AbortSignal,
): Promise<Response | null> {
  const timeout = AbortSignal.timeout(timeoutMs)
  const signal = cancel === undefined ? timeout : AbortSignal.any([timeout, cancel])
  try {
    return await fetch(url, { ...init, signal })
  } catch {
    return null
  }
}

async function readResult<S extends v.GenericSchema>(
  response: Response | null,
  schema: S,
): Promise<ApiResult<v.InferOutput<S>>> {
  if (response === null) {
    return { kind: 'network-error' }
  }
  const unexpected: Extract<ApiFailure, { kind: 'http-error' }> = {
    kind: 'http-error',
    status: response.status,
    error: null,
  }
  let body: unknown
  try {
    body = await response.json()
  } catch (error) {
    // Not JSON is an unexpected answer; any other error means the reading was cut off.
    if (error instanceof SyntaxError) {
      return unexpected
    }
    return { kind: 'network-error' }
  }
  if (response.ok) {
    const answer = v.safeParse(schema, body)
    return answer.success ? { kind: 'ok', value: answer.output } : unexpected
  }
  const refusal = v.safeParse(vApiError, body)
  return { ...unexpected, error: refusal.success ? refusal.output : null }
}

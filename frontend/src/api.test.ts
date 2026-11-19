import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { UploadInstructions } from './generated'
import { createTask, getTask, loadApiConfig, uploadFile } from './api'
import type { ApiResult } from './api'
import { failureMessage } from './messages'

const upload: UploadInstructions = {
  url: 'https://bucket.example/object',
  method: 'PUT',
  headers: {},
}
const file = new File(['x'], 'a.png', { type: 'image/png' })
const notCancelled = new AbortController().signal

const fetchMock = vi.fn<typeof fetch>()

function respond(status: number) {
  fetchMock.mockResolvedValueOnce(new Response(null, { status }))
}

function failToConnect() {
  fetchMock.mockRejectedValueOnce(new TypeError('failed'))
}

async function runUpload() {
  const result = uploadFile(file, upload, notCancelled)
  await vi.runAllTimersAsync()
  return result
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  fetchMock.mockReset()
})

describe('uploadFile', () => {
  it('counts 412 as success', async () => {
    respond(412)
    expect(await runUpload()).toEqual({ kind: 'stored' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['409', () => respond(409)],
    ['503', () => respond(503)],
    ['a network error', failToConnect],
  ])('repeats after %s and succeeds', async (_name, firstAnswer) => {
    firstAnswer()
    respond(200)
    expect(await runUpload()).toEqual({ kind: 'stored' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('reports an unconfirmed upload after 3 attempts', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 503 }))
    expect(await runUpload()).toEqual({ kind: 'unconfirmed' })
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('reports the open question when a final refusal follows an attempt that may have stored the file', async () => {
    failToConnect()
    respond(403)
    expect(await runUpload()).toEqual({ kind: 'unconfirmed' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('does not repeat after 403', async () => {
    respond(403)
    expect(await runUpload()).toEqual({ kind: 'http-error', status: 403, error: null })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

const config = { apiUrl: 'https://api.example', retryBudgetSeconds: 690 }
const TASK_ID = '0b8f2c5e-6c1a-4d0e-9a3b-7a1c2d3e4f50'

const createdBody = {
  id: TASK_ID,
  upload: {
    method: 'PUT',
    url: `https://bucket.s3.amazonaws.com/originals/${TASK_ID}?X-Amz-Signature=abc`,
    headers: {
      'content-type': 'image/jpeg',
      'content-length': '3145728',
      'if-none-match': '*',
      'content-disposition': 'inline',
      'cache-control': 'private',
    },
  },
}
const variant = {
  name: 'thumbnail',
  label: 'Thumbnail',
  contentType: 'image/jpeg',
  width: 200,
  height: 150,
  size: 8192,
  viewUrl: `https://bucket.s3.amazonaws.com/derived/${TASK_ID}/thumbnail.jpg?sig=3`,
  downloadUrl: `https://bucket.s3.amazonaws.com/derived/${TASK_ID}/thumbnail.jpg?sig=4`,
}
const doneBody = {
  id: TASK_ID,
  status: 'DONE',
  original: {
    contentType: 'image/jpeg',
    size: 3145728,
    viewUrl: `https://bucket.s3.amazonaws.com/originals/${TASK_ID}?sig=1`,
    downloadUrl: `https://bucket.s3.amazonaws.com/originals/${TASK_ID}?sig=2`,
  },
  variants: [variant],
}

function answer(body: unknown, status = 200) {
  fetchMock.mockResolvedValueOnce(Response.json(body, { status }))
}

function unexpected(status: number) {
  return { kind: 'http-error', status, error: null }
}

function userText(result: ApiResult<unknown>): string {
  if (result.kind === 'ok') {
    throw new Error('the answer was accepted')
  }
  return failureMessage(result)
}

describe('getTask', () => {
  it('returns a task of the spec, with an int64 size above 2 to the power of 53 as a number', async () => {
    const body = { ...doneBody, variants: [{ ...variant, size: 2 ** 60 }] }
    answer(body)
    expect(await getTask(config, TASK_ID, notCancelled)).toEqual({ kind: 'ok', value: body })
  })

  it('takes an answer outside the spec, such as an int64 size given as text, for an unexpected one', async () => {
    answer({ ...doneBody, variants: [{ ...variant, size: '8192' }] })
    const result = await getTask(config, TASK_ID, notCancelled)
    expect(result).toEqual(unexpected(200))
    expect(userText(result)).toBe('Unexpected response from the server (HTTP 200).')
  })

  it('takes an HTML page for an unexpected answer and no answer for a lost connection', async () => {
    fetchMock.mockResolvedValueOnce(new Response('<html>Sign in to the network</html>', { status: 200 }))
    expect(await getTask(config, TASK_ID, notCancelled)).toEqual(unexpected(200))
    failToConnect()
    expect(await getTask(config, TASK_ID, notCancelled)).toEqual({ kind: 'network-error' })
  })

  it('takes the 429 answer of the Function URL, not an ApiError, for an unexpected one', async () => {
    answer({ Message: 'Rate Exceeded.' }, 429)
    expect(await getTask(config, TASK_ID, notCancelled)).toEqual(unexpected(429))
  })
})

describe('createTask', () => {
  const request = { fileName: 'holiday.jpg', contentType: 'image/jpeg', size: 3145728 }

  it('accepts a created task with a field the spec does not list', async () => {
    answer({ ...createdBody, queuePosition: 3 }, 201)
    expect(await createTask(config, request, notCancelled)).toEqual({ kind: 'ok', value: createdBody })
  })

  it.each([
    [
      { code: 'FILE_TOO_LARGE', message: 'The file is larger than the allowed size.', maxUploadBytes: 20971520 },
      'The file is too large. The limit is 20 MiB.',
    ],
    [{ code: 'INVALID_REQUEST', message: 'The request body is not valid.' }, 'The request was not valid.'],
  ])('keeps the error of the spec for a refusal: %o', async (error, text) => {
    answer(error, 400)
    const result = await createTask(config, request, notCancelled)
    expect(result).toEqual({ kind: 'http-error', status: 400, error })
    expect(userText(result)).toBe(text)
  })
})

describe('createTask on a server that cannot answer', () => {
  const request = { fileName: 'holiday.jpg', contentType: 'image/jpeg', size: 3145728 }

  it.each([
    [429, { Message: 'Rate Exceeded.' }],
    [502, { message: 'Internal server error' }],
  ])('shows one message for HTTP %i without an error of the API', async (status, body) => {
    answer(body, status)
    expect(userText(await createTask(config, request, notCancelled))).toBe('The server could not answer. Try again.')
  })

  it('keeps the text of the API for its own INTERNAL_ERROR', async () => {
    answer({ code: 'INTERNAL_ERROR', message: 'Internal error.' }, 500)
    expect(userText(await createTask(config, request, notCancelled))).toBe('Something went wrong on our side. Try again.')
  })
})

describe('loadApiConfig', () => {
  it('reads the API address and the retry budget', async () => {
    fetchMock.mockResolvedValueOnce(Response.json({ apiUrl: 'https://api.example', retryBudgetSeconds: 690 }))
    expect(await loadApiConfig()).toEqual({ apiUrl: 'https://api.example', retryBudgetSeconds: 690 })
  })

  it('refuses a config without the retry budget or with a fractional one', async () => {
    for (const budget of [{}, { retryBudgetSeconds: 1.5 }]) {
      fetchMock.mockResolvedValueOnce(Response.json({ apiUrl: 'https://api.example', ...budget }))
      expect(await loadApiConfig(), JSON.stringify(budget)).toBeNull()
    }
  })
})

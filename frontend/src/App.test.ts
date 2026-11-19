// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import type { App as VueApp } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { createTask, getTask, loadApiConfig, sleep, uploadFile } from './api'
import type { ApiConfig, ApiResult } from './api'
import { ContentType } from './generated'
import type { CreateTaskResponse, Task } from './generated'
import { pageText, takingLonger } from './messages'

vi.mock('./api', () => ({
  createTask: vi.fn(),
  getTask: vi.fn(),
  loadApiConfig: vi.fn(),
  sleep: vi.fn(),
  uploadFile: vi.fn(),
}))

const CONFIG: ApiConfig = { apiUrl: 'https://api.example', retryBudgetSeconds: 690 }
const CONFIG_RETRY_MINUTES = 12
const TASK_ID = '11111111-1111-4111-8111-111111111111'
const MINUTE_MS = 60_000

const created: CreateTaskResponse = {
  id: TASK_ID,
  upload: { method: 'PUT', url: 'https://bucket.example/object', headers: {} },
}
const processing: Task = { id: TASK_ID, status: 'PROCESSING' }
const variant = {
  name: 'thumbnail',
  label: 'Thumbnail',
  contentType: ContentType.IMAGE_JPEG,
  width: 200,
  height: 150,
  size: 1,
  viewUrl: 'https://bucket.example/thumbnail/view',
  downloadUrl: 'https://bucket.example/thumbnail/download',
}
const done: Task & { status: 'DONE' } = {
  id: TASK_ID,
  status: 'DONE',
  original: { contentType: ContentType.IMAGE_PNG, size: 1, viewUrl: 'https://bucket.example/view', downloadUrl: 'https://bucket.example/download' },
  variants: [variant],
}

function pendingSince(minutesAgo: number): Task {
  return { id: TASK_ID, status: 'PENDING_UPLOAD', createdAt: new Date(Date.now() - minutesAgo * MINUTE_MS).toISOString() }
}

let app: VueApp | null = null
let page: HTMLElement

function mountPage() {
  page = document.createElement('div')
  app = createApp(App)
  app.mount(page)
}

function statusText(): string | undefined {
  return page.querySelector('[role="status"]')?.textContent?.trim()
}

function fileInput(): HTMLInputElement | null {
  return page.querySelector('input[type="file"]')
}

async function settle() {
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

async function openPage(search = '') {
  history.replaceState(null, '', `/${search}`)
  mountPage()
  await settle()
}

async function chooseFile() {
  const input = fileInput()
  if (input === null) {
    throw new Error('no file input')
  }
  Object.defineProperty(input, 'files', { value: [new File(['x'], 'a.png', { type: 'image/png' })] })
  input.dispatchEvent(new Event('change'))
  await settle()
}

function answerLater() {
  let answer: (result: ApiResult<Task>) => void = () => {}
  vi.mocked(getTask).mockReturnValueOnce(
    new Promise((resolve) => {
      answer = resolve
    }),
  )
  return async (task: Task) => {
    answer({ kind: 'ok', value: task })
    await settle()
  }
}

beforeEach(() => {
  history.replaceState(null, '', '/')
  vi.mocked(loadApiConfig).mockResolvedValue(CONFIG)
  vi.mocked(createTask).mockResolvedValue({ kind: 'ok', value: created })
  vi.mocked(sleep).mockReturnValue(new Promise(() => {}))
})

afterEach(() => {
  app?.unmount()
  app = null
  vi.resetAllMocks()
  vi.useRealTimers()
})

describe('App', () => {
  it('shows the title and a loading text at once and the idle page when the config arrives', async () => {
    let resolveConfig: (config: ApiConfig) => void = () => {}
    vi.mocked(loadApiConfig).mockReturnValue(
      new Promise((resolve) => {
        resolveConfig = resolve
      }),
    )
    mountPage()

    expect(page.querySelector('h1')?.textContent).toBe(pageText.title)
    expect(page.textContent).toContain(pageText.loading)
    expect(page.querySelector('input[type="file"]')).toBeNull()

    resolveConfig(CONFIG)
    await settle()

    expect(page.textContent).not.toContain(pageText.loading)
    expect(page.querySelector('input[type="file"]')?.hasAttribute('disabled')).toBe(false)
    expect(statusText()).toBe('')
  })

  it('puts the first status after the config into the live region that was there from the start', async () => {
    history.replaceState(null, '', '/?task=bad')
    let resolveConfig: (config: ApiConfig) => void = () => {}
    vi.mocked(loadApiConfig).mockReturnValue(
      new Promise((resolve) => {
        resolveConfig = resolve
      }),
    )
    mountPage()
    const status = page.querySelector('[role="status"]')

    expect(status?.textContent?.trim()).toBe('')

    resolveConfig(CONFIG)
    await settle()

    expect(page.querySelector('[role="status"]')).toBe(status)
    expect(statusText()).toBe(pageText.invalidLink)
  })

  it('shows that the settings could not be loaded when the config fails', async () => {
    vi.mocked(loadApiConfig).mockResolvedValue(null)
    mountPage()
    await settle()

    expect(page.querySelector('[role="alert"]')?.textContent).toBe(pageText.configUnavailable)
    expect(page.querySelector('input[type="file"]')).toBeNull()
  })

  it('shows a linked task as loading and then as waiting for the upload', async () => {
    const answer = answerLater()
    await openPage(`?task=${TASK_ID}`)

    expect(statusText()).toBe(pageText.loading)
    expect(fileInput()?.disabled).toBe(true)

    await answer(pendingSince(1))

    expect(statusText()).toBe(pageText.waitingForUpload)
  })

  it('shows the hint for a linked upload that did not start, frees the file input and goes on polling', async () => {
    vi.mocked(getTask).mockResolvedValueOnce({ kind: 'ok', value: pendingSince(16) })
    vi.mocked(sleep).mockResolvedValueOnce(undefined)
    const answer = answerLater()
    await openPage(`?task=${TASK_ID}`)

    expect(statusText()).toBe(pageText.uploadNotStarted)
    expect(fileInput()?.disabled).toBe(false)

    await answer(processing)

    expect(statusText()).toBe(pageText.processing)
  })

  it('shows our stored upload as waiting for processing and then the original and each version', async () => {
    vi.mocked(uploadFile).mockResolvedValue({ kind: 'stored' })
    vi.mocked(sleep).mockResolvedValueOnce(undefined)
    const answer = answerLater()
    await openPage()
    await chooseFile()

    expect(statusText()).toBe(pageText.uploadedWaiting)

    await answer(done)

    expect(statusText()).toBe(pageText.done)
    expect(fileInput()?.disabled).toBe(false)
    const links = [...page.querySelectorAll('li a')].map((link) => [link.getAttribute('aria-label'), link.getAttribute('href')])
    expect(links).toEqual([
      ['View Original', done.original.viewUrl],
      ['Download Original', done.original.downloadUrl],
      ['View Thumbnail (200×150)', variant.viewUrl],
      ['Download Thumbnail (200×150)', variant.downloadUrl],
    ])
  })

  it('shows why a linked task was rejected or failed and frees the file input for another file', async () => {
    const outcomes: [Task, string][] = [
      [{ id: TASK_ID, status: 'INPUT_REJECTED', error: { code: 'UNDECODABLE_IMAGE' } }, 'The file could not be read as an image.'],
      [{ id: TASK_ID, status: 'FAILED', error: { code: 'ORIGINAL_MISSING' } }, `The uploaded file was not found. Task ${TASK_ID}.`],
    ]
    for (const [task, reason] of outcomes) {
      vi.mocked(getTask).mockResolvedValueOnce({ kind: 'ok', value: task })
      await openPage(`?task=${TASK_ID}`)

      expect(statusText(), task.status).toContain(reason)
      expect(statusText(), task.status).toContain(pageText.chooseAnother)
      expect(fileInput()?.disabled, task.status).toBe(false)
      app?.unmount()
      app = null
    }
  })

  it('offers to check the status or to upload again after an unconfirmed upload', async () => {
    vi.mocked(uploadFile).mockResolvedValue({ kind: 'unconfirmed' })
    await openPage()
    await chooseFile()

    expect(statusText()).toBe(pageText.uploadUnconfirmed)
    const buttons = [...page.querySelectorAll('button')]
    expect(buttons.map((button) => button.textContent)).toEqual([pageText.checkStatus, pageText.uploadAgain])

    vi.mocked(getTask).mockResolvedValue({ kind: 'ok', value: processing })
    buttons[0]?.click()
    await settle()

    expect(statusText()).toBe(pageText.processing)
  })

  it('shows a lost connection together with the processing status', async () => {
    vi.mocked(getTask)
      .mockResolvedValueOnce({ kind: 'ok', value: processing })
      .mockResolvedValueOnce({ kind: 'network-error' })
    vi.mocked(sleep).mockResolvedValueOnce(undefined)
    await openPage(`?task=${TASK_ID}`)

    expect(statusText()).toBe(`${pageText.pollingNoConnection}${pageText.processing}`)
  })

  it('adds the slow hint when processing takes longer than usual', async () => {
    vi.useFakeTimers({ toFake: ['performance'] })
    const answer = answerLater()
    await openPage(`?task=${TASK_ID}`)

    vi.advanceTimersByTime(3 * MINUTE_MS)
    await answer(processing)

    expect(statusText()).toBe(`${pageText.processing}${takingLonger(CONFIG_RETRY_MINUTES)}`)
  })
})

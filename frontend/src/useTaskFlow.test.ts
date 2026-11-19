// @vitest-environment happy-dom
import { createApp } from 'vue'
import type { App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createTask, getTask, loadApiConfig, sleep, uploadFile } from './api'
import type { ApiConfig, ApiResult } from './api'
import { ContentType } from './generated'
import type { CreateTaskResponse, Task } from './generated'
import { failureMessage } from './messages'
import { useTaskFlow } from './useTaskFlow'

vi.mock('./api', () => ({
  createTask: vi.fn(),
  getTask: vi.fn(),
  loadApiConfig: vi.fn(),
  sleep: vi.fn(),
  uploadFile: vi.fn(),
}))

type Flow = ReturnType<typeof useTaskFlow>

const TASK_ID = '11111111-1111-4111-8111-111111111111'
const CONFIG: ApiConfig = { apiUrl: 'https://api.example', retryBudgetSeconds: 690 }

const created: CreateTaskResponse = {
  id: TASK_ID,
  upload: { method: 'PUT', url: 'https://bucket.example/object', headers: {} },
}
const doneTask: Task = {
  id: TASK_ID,
  status: 'DONE',
  original: {
    contentType: ContentType.IMAGE_PNG,
    size: 1,
    viewUrl: 'https://bucket.example/view',
    downloadUrl: 'https://bucket.example/download',
  },
  variants: [],
}

const mounted: App[] = []

async function mountFlow(): Promise<Flow> {
  const flows: Flow[] = []
  const app = createApp({
    setup() {
      flows.push(useTaskFlow())
      return () => null
    },
  })
  app.mount(document.createElement('div'))
  mounted.push(app)
  await settle()
  const flow = flows[0]
  if (flow === undefined) {
    throw new Error('setup did not run')
  }
  return flow
}

function openTaskLink() {
  history.replaceState(null, '', `/?task=${TASK_ID}`)
}

// Lets every promise that is ready run; the polling waits (`sleep`) never finish unless a test allows one.
function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}

async function chooseFile(flow: Flow) {
  const input = document.createElement('input')
  input.type = 'file'
  Object.defineProperty(input, 'files', { value: [new File(['x'], 'a.png', { type: 'image/png' })] })
  input.addEventListener('change', flow.onFileChosen)
  input.dispatchEvent(new Event('change'))
  await settle()
}

beforeEach(() => {
  history.replaceState(null, '', '/')
  vi.mocked(sleep).mockReturnValue(new Promise(() => {}))
  vi.mocked(createTask).mockResolvedValue({ kind: 'ok', value: created })
  vi.mocked(loadApiConfig).mockResolvedValue(CONFIG)
})

afterEach(() => {
  mounted.splice(0).forEach((app) => app.unmount())
  vi.resetAllMocks()
})

describe('useTaskFlow', () => {
  it('keeps the task after an unconfirmed upload and drops it after a refusal', async () => {
    const flow = await mountFlow()
    vi.mocked(uploadFile).mockResolvedValueOnce({ kind: 'unconfirmed' })
    await chooseFile(flow)

    expect(flow.state.value).toEqual({ kind: 'unconfirmed', taskId: TASK_ID })
    expect(location.search).toBe(`?task=${TASK_ID}`)

    vi.mocked(getTask).mockResolvedValue({ kind: 'ok', value: doneTask })
    flow.checkStatus()
    await settle()

    expect(getTask).toHaveBeenCalledWith(CONFIG, TASK_ID, expect.any(AbortSignal))
    expect(flow.state.value).toEqual({ kind: 'finished', task: doneTask })

    const notCreated = { kind: 'http-error', status: 400, error: null } as const
    vi.mocked(createTask).mockResolvedValueOnce(notCreated)
    const entries = history.length
    await chooseFile(flow)

    expect(location.search).toBe('')
    expect(history.length).toBe(entries + 1)
    expect(flow.state.value).toEqual({ kind: 'problem', message: failureMessage(notCreated) })

    const refusal = { kind: 'http-error', status: 403, error: null } as const
    vi.mocked(uploadFile).mockResolvedValueOnce(refusal)
    const beforePut = history.length
    await chooseFile(flow)

    expect(location.search).toBe('')
    expect(history.length).toBe(beforePut + 1)
    expect(flow.state.value).toEqual({ kind: 'problem', message: failureMessage(refusal) })
  })

  it('drops the answer of a run that the user has left', async () => {
    openTaskLink()
    let answer: (result: ApiResult<Task>) => void = () => {}
    vi.mocked(getTask).mockReturnValue(
      new Promise((resolve) => {
        answer = resolve
      }),
    )
    const flow = await mountFlow()
    expect(getTask).toHaveBeenCalledTimes(1)

    flow.goHome()
    answer({ kind: 'ok', value: doneTask })
    await settle()

    expect(flow.state.value).toEqual({ kind: 'idle' })
    expect(getTask).toHaveBeenCalledTimes(1)
    expect(sleep).not.toHaveBeenCalled()
  })

  it('ignores the back button and "New upload" until the config is there and then follows the current URL', async () => {
    let resolveConfig: (config: ApiConfig) => void = () => {}
    vi.mocked(loadApiConfig).mockReturnValue(
      new Promise((resolve) => {
        resolveConfig = resolve
      }),
    )
    vi.mocked(getTask).mockResolvedValue({ kind: 'ok', value: { id: TASK_ID, status: 'PROCESSING' } })
    const flow = await mountFlow()

    openTaskLink()
    window.dispatchEvent(new PopStateEvent('popstate'))
    flow.goHome()
    await settle()

    expect(getTask).not.toHaveBeenCalled()

    resolveConfig(CONFIG)
    await settle()

    expect(getTask).toHaveBeenCalledTimes(1)
    expect(getTask).toHaveBeenCalledWith(CONFIG, TASK_ID, expect.any(AbortSignal))

    history.replaceState(null, '', '/')
    window.dispatchEvent(new PopStateEvent('popstate'))
    await settle()

    expect(flow.state.value).toEqual({ kind: 'idle' })
  })
})

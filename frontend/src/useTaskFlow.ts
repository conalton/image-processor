import { computed, onMounted, onUnmounted, ref, shallowRef } from 'vue'
import { createTask, getTask, loadApiConfig, sleep, uploadFile } from './api'
import type { ApiConfig } from './api'
import type { TaskId } from './generated'
import { takingLonger } from './messages'
import { initialState, isBusy, isPolling, transition } from './taskFlowMachine'
import type { FlowEvent, Origin } from './taskFlowMachine'
import { pushHomeUrl, pushTaskUrl, replaceHomeUrl, taskIdFromSearch } from './taskUrl'

const POLL_INTERVALS_MS = [1000, 2000, 3000]
const POLL_STEADY_MS = 5000
const MIN_POLL_BUDGET_MS = 60 * 60 * 1000
const POLL_BUDGET_MARGIN_MS = 5 * 60 * 1000

function minutesUp(ms: number): number {
  return Math.ceil(ms / 60_000)
}

type Settings = { config: ApiConfig; retryMinutes: number; pollBudgetMs: number; pollBudgetMinutes: number }

function settingsFor(config: ApiConfig): Settings {
  const retryBudgetMs = config.retryBudgetSeconds * 1000
  // Outlasts the retry budget: the page must still be polling when the dead-letter function writes FAILED.
  const pollBudgetMs = Math.max(MIN_POLL_BUDGET_MS, retryBudgetMs + POLL_BUDGET_MARGIN_MS)
  return {
    config,
    retryMinutes: minutesUp(retryBudgetMs),
    pollBudgetMs,
    pollBudgetMinutes: minutesUp(pollBudgetMs),
  }
}

export function useTaskFlow() {
  const state = shallowRef(initialState)
  const settings = shallowRef<Settings | null>(null)
  const inputKey = ref(0)

  const busy = computed(() => isBusy(state.value))
  const slowHint = computed(() => {
    const current = state.value
    const loaded = settings.value
    return current.kind === 'waiting' && current.slow && loaded !== null ? takingLonger(loaded.retryMinutes) : null
  })

  // A new run aborts the older run's requests and drops its results.
  let runCancel = new AbortController()

  onMounted(async () => {
    window.addEventListener('popstate', onPopState)
    const run = runCancel.signal
    const config = await loadApiConfig()
    if (run.aborted) {
      return
    }
    if (config === null) {
      dispatch({ type: 'configFailed' })
      return
    }
    const loaded = settingsFor(config)
    settings.value = loaded
    dispatch({ type: 'configLoaded' })
    showTaskFromUrl(loaded)
  })

  onUnmounted(() => {
    window.removeEventListener('popstate', onPopState)
    // Ends the polling loop after its current wait.
    runCancel.abort()
  })

  function onPopState() {
    if (settings.value !== null) {
      showTaskFromUrl(settings.value)
    }
  }

  function dispatch(event: FlowEvent) {
    state.value = transition(state.value, event)
  }

  function startRun(): AbortSignal {
    runCancel.abort()
    runCancel = new AbortController()
    // A new key gives a new file input: choosing the same file again fires `change`.
    inputKey.value++
    return runCancel.signal
  }

  function showTaskFromUrl(loaded: Settings) {
    const run = startRun()
    const target = taskIdFromSearch(location.search)
    switch (target.kind) {
      case 'none':
        dispatch({ type: 'homeOpened' })
        return
      case 'malformed':
        dispatch({ type: 'linkMalformed' })
        return
      case 'valid':
        poll(loaded, target.id, run, 'link')
        return
      default: {
        const unreachable: never = target
        return unreachable
      }
    }
  }

  function goHome() {
    if (settings.value === null) {
      return
    }
    startRun()
    dispatch({ type: 'homeOpened' })
    if (location.search !== '') {
      pushHomeUrl()
    }
  }

  async function onFileChosen(event: Event) {
    const input = event.target
    if (!(input instanceof HTMLInputElement)) {
      return
    }
    const file = input.files?.[0]
    if (file === undefined || settings.value === null) {
      return
    }
    await uploadAndWait(settings.value, file)
  }

  async function uploadAndWait(loaded: Settings, file: File) {
    const run = startRun()
    dispatch({ type: 'uploadStarted' })

    const request = { fileName: file.name, contentType: file.type, size: file.size }
    const created = await createTask(loaded.config, request, run)
    if (run.aborted) {
      return
    }
    if (created.kind !== 'ok') {
      if (location.search !== '') {
        pushHomeUrl()
      }
      dispatch({ type: 'uploadFailed', failure: created })
      return
    }
    pushTaskUrl(created.value.id)
    const uploaded = await uploadFile(file, created.value.upload, run)
    if (run.aborted) {
      return
    }
    switch (uploaded.kind) {
      case 'stored':
        await poll(loaded, created.value.id, run, 'upload')
        return
      case 'unconfirmed':
        dispatch({ type: 'uploadUnconfirmed', taskId: created.value.id })
        return
      case 'http-error':
        replaceHomeUrl()
        dispatch({ type: 'uploadFailed', failure: uploaded })
        return
      default: {
        const unreachable: never = uploaded
        return unreachable
      }
    }
  }

  function checkStatus() {
    const current = state.value
    if (current.kind === 'unconfirmed' && settings.value !== null) {
      const run = startRun()
      poll(settings.value, current.taskId, run, 'link')
    }
  }

  async function poll(loaded: Settings, id: TaskId, run: AbortSignal, origin: Origin) {
    dispatch({ type: 'pollingStarted', origin })
    // Monotonic: a system clock change does not stretch or cut the polling budget or the slow hint.
    const startedAt = performance.now()
    if (origin === 'link' && !(await getAndShow(loaded.config, id, run, startedAt))) {
      return
    }
    for (let attempt = 0; ; attempt++) {
      if (performance.now() - startedAt >= loaded.pollBudgetMs) {
        dispatch({ type: 'pollingGaveUp', minutes: loaded.pollBudgetMinutes })
        return
      }
      await sleep(POLL_INTERVALS_MS[attempt] ?? POLL_STEADY_MS)
      if (run.aborted || !(await getAndShow(loaded.config, id, run, startedAt))) {
        return
      }
    }
  }

  // false when the polling has to end.
  async function getAndShow(config: ApiConfig, id: TaskId, run: AbortSignal, startedAt: number): Promise<boolean> {
    const result = await getTask(config, id, run)
    if (run.aborted) {
      return false
    }
    if (result.kind === 'ok') {
      dispatch({ type: 'taskFetched', task: result.value, waitedMs: performance.now() - startedAt, nowMs: Date.now() })
    } else {
      dispatch({ type: 'pollFailed', failure: result })
    }
    return isPolling(state.value)
  }

  return { state, inputKey, busy, slowHint, onFileChosen, goHome, checkStatus }
}

import type { ApiFailure } from './api'
import type { Task, TaskId } from './generated'
import { failureMessage, isServerTrouble, pageText, pollingGaveUp } from './messages'

export type FinishedTask = Extract<Task, { status: 'DONE' | 'INPUT_REJECTED' | 'FAILED' }>

// link also covers "Check status".
export type Origin = 'link' | 'upload'
export type Stage = 'unknown' | 'pending' | 'processing'

export type State =
  | { kind: 'loadingConfig' }
  | { kind: 'configFailed' }
  | { kind: 'idle' }
  | { kind: 'uploading' }
  | { kind: 'unconfirmed'; taskId: TaskId }
  | { kind: 'waiting'; origin: Origin; stage: Stage; slow: boolean; notice: string | null }
  // Upload or processing did not start in time: the file input is free again; polling goes on as either may start.
  | { kind: 'stalled'; notice: string }
  | { kind: 'finished'; task: FinishedTask }
  | { kind: 'problem'; message: string }

export type FlowEvent =
  | { type: 'configLoaded' }
  | { type: 'configFailed' }
  | { type: 'homeOpened' }
  | { type: 'linkMalformed' }
  | { type: 'uploadStarted' }
  | { type: 'uploadFailed'; failure: ApiFailure }
  | { type: 'uploadUnconfirmed'; taskId: TaskId }
  | { type: 'pollingStarted'; origin: Origin }
  | { type: 'taskFetched'; task: Task; waitedMs: number; nowMs: number }
  | { type: 'pollFailed'; failure: ApiFailure }
  | { type: 'pollingGaveUp'; minutes: number }

type Polling = Extract<State, { kind: 'waiting' | 'stalled' }>

// A linked task still in PENDING_UPLOAD after this gets a hint. Every PUT of this page ends before UPLOAD_URL_LIFETIME
// (UrlLifetimes.kt) runs out (timeouts in api.ts); the rest is margin for the S3 event. createdAt is server time
// compared with the browser clock: only a hint depends on it.
const UPLOAD_WAIT_LIMIT_MS = 15 * 60 * 1000
const SLOW_PROCESSING_MS = 2 * 60 * 1000

export const initialState: State = { kind: 'loadingConfig' }

export function isPolling(state: State): state is Polling {
  return state.kind === 'waiting' || state.kind === 'stalled'
}

export function isBusy(state: State): boolean {
  return state.kind === 'uploading' || state.kind === 'waiting'
}

export function transition(state: State, event: FlowEvent): State {
  if (state.kind === 'loadingConfig') {
    switch (event.type) {
      case 'configLoaded':
        return { kind: 'idle' }
      case 'configFailed':
        return { kind: 'configFailed' }
      default:
        return state
    }
  }
  if (state.kind === 'configFailed') {
    return state
  }
  switch (event.type) {
    case 'configLoaded':
    case 'configFailed':
      return state
    case 'homeOpened':
      return { kind: 'idle' }
    case 'linkMalformed':
      return problem(pageText.invalidLink)
    case 'uploadStarted':
      return { kind: 'uploading' }
    case 'pollingStarted':
      return waiting(event.origin, 'unknown', false)
    case 'uploadFailed':
      return state.kind === 'uploading' ? problem(failureMessage(event.failure)) : state
    case 'uploadUnconfirmed':
      return state.kind === 'uploading' ? { kind: 'unconfirmed', taskId: event.taskId } : state
    case 'taskFetched':
      return isPolling(state) ? taskFetched(state, event.task, event.waitedMs, event.nowMs) : state
    case 'pollFailed':
      return isPolling(state) ? pollFailed(state, event.failure) : state
    case 'pollingGaveUp':
      return isPolling(state) ? problem(pollingGaveUp(event.minutes)) : state
    default: {
      const unreachable: never = event
      return unreachable
    }
  }
}

function problem(message: string): State {
  return { kind: 'problem', message }
}

function waiting(origin: Origin, stage: Stage, slow: boolean): State {
  return { kind: 'waiting', origin, stage, slow, notice: null }
}

function taskFetched(state: Polling, task: Task, waitedMs: number, nowMs: number): State {
  // A stalled state comes only from a linked task.
  const origin = state.kind === 'waiting' ? state.origin : 'link'
  const slow = waitedMs >= SLOW_PROCESSING_MS
  switch (task.status) {
    case 'PENDING_UPLOAD':
      if (origin === 'link' && nowMs - Date.parse(task.createdAt) >= UPLOAD_WAIT_LIMIT_MS) {
        return { kind: 'stalled', notice: pageText.uploadNotStarted }
      }
      // Our own task stays PENDING_UPLOAD if every worker attempt fails before PROCESSING: same retries, same wait.
      return waiting(origin, 'pending', origin === 'upload' && slow)
    case 'PROCESSING':
      return waiting(origin, 'processing', slow)
    case 'DONE':
    case 'INPUT_REJECTED':
    case 'FAILED':
      return { kind: 'finished', task }
    default: {
      const unreachable: never = task
      return unreachable
    }
  }
}

function pollFailed(state: Polling, failure: ApiFailure): State {
  const notice = transientNotice(failure)
  if (notice === null) {
    return problem(failureMessage(failure))
  }
  return state.kind === 'waiting' ? { ...state, notice } : { kind: 'stalled', notice }
}

function transientNotice(failure: ApiFailure): string | null {
  if (failure.kind === 'network-error') {
    return pageText.pollingNoConnection
  }
  return isServerTrouble(failure.status) ? pageText.pollingServerBusy : null
}

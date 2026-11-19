import * as v from 'valibot'
import type { TaskId } from './generated'
import { vTaskId } from './generated/valibot.gen'

export type TaskIdInUrl = { kind: 'none' } | { kind: 'valid'; id: TaskId } | { kind: 'malformed' }

const TASK_PARAMETER = 'task'

export function taskIdFromSearch(search: string): TaskIdInUrl {
  const value = new URLSearchParams(search).get(TASK_PARAMETER)
  if (value === null) {
    return { kind: 'none' }
  }
  return v.is(vTaskId, value) ? { kind: 'valid', id: value } : { kind: 'malformed' }
}

// The page is a file in a bucket (/index.html), which has no index document: its own path is kept, never "/".
export function pushTaskUrl(id: TaskId) {
  history.pushState(null, '', `${location.pathname}?${TASK_PARAMETER}=${encodeURIComponent(id)}`)
}

export function homeUrl() {
  return location.pathname
}

export function pushHomeUrl() {
  history.pushState(null, '', homeUrl())
}

export function replaceHomeUrl() {
  history.replaceState(null, '', homeUrl())
}

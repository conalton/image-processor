import { describe, expect, it } from 'vitest'
import type { Task } from './generated'
import { failureMessage, pageText, pollingGaveUp } from './messages'
import { transition } from './taskFlowMachine'
import type { FlowEvent, State } from './taskFlowMachine'

const TASK_ID = '11111111-1111-4111-8111-111111111111'
const MINUTE_MS = 60_000
const NOW_MS = Date.parse('2026-01-01T12:00:00Z')

const processing: Task = { id: TASK_ID, status: 'PROCESSING' }
const networkError = { kind: 'network-error' } as const
const notFound = { kind: 'http-error', status: 404, error: null } as const

function pendingSince(minutesAgo: number): Task {
  return { id: TASK_ID, status: 'PENDING_UPLOAD', createdAt: new Date(NOW_MS - minutesAgo * MINUTE_MS).toISOString() }
}

function fetched(task: Task, waitedMinutes = 0): FlowEvent {
  return { type: 'taskFetched', task, waitedMs: waitedMinutes * MINUTE_MS, nowMs: NOW_MS }
}

const idle: State = { kind: 'idle' }

function run(events: FlowEvent[], from: State = idle): State {
  return events.reduce(transition, from)
}

describe('transition', () => {
  it('hints at a linked upload that did not start within 15 minutes and recovers when it progresses', () => {
    const waiting = run([{ type: 'pollingStarted', origin: 'link' }])

    expect(run([fetched(pendingSince(14))], waiting)).toMatchObject({ kind: 'waiting', stage: 'pending' })
    const stalled = run([fetched(pendingSince(15))], waiting)
    expect(stalled).toEqual({ kind: 'stalled', notice: pageText.uploadNotStarted })

    expect(run([fetched(processing)], stalled)).toMatchObject({ kind: 'waiting', origin: 'link', stage: 'processing' })
    expect(run([fetched(pendingSince(30))], stalled)).toEqual(stalled)
    expect(run([{ type: 'pollFailed', failure: networkError }], stalled)).toEqual({
      kind: 'stalled',
      notice: pageText.pollingNoConnection,
    })
  })

  it('gives our own upload no hint however old the task looks', () => {
    const state = run([{ type: 'pollingStarted', origin: 'upload' }, fetched(pendingSince(60))])
    expect(state).toMatchObject({ kind: 'waiting', origin: 'upload', stage: 'pending' })
  })

  it('marks the wait as slow after two minutes of processing or of our own queued upload', () => {
    const link = run([{ type: 'pollingStarted', origin: 'link' }])
    const upload = run([{ type: 'pollingStarted', origin: 'upload' }])

    expect(run([fetched(processing, 1.9)], link)).toMatchObject({ slow: false })
    expect(run([fetched(processing, 2)], link)).toMatchObject({ slow: true })
    expect(run([fetched(pendingSince(1), 2)], upload)).toMatchObject({ stage: 'pending', slow: true })
    expect(run([fetched(pendingSince(1), 5)], link)).toMatchObject({ stage: 'pending', slow: false })
  })

  it('keeps waiting through a lost connection or a busy server and drops the notice on the next answer', () => {
    const waiting = run([{ type: 'pollingStarted', origin: 'link' }, fetched(processing, 3)])

    const offline = run([{ type: 'pollFailed', failure: networkError }], waiting)
    expect(offline).toEqual({ ...waiting, notice: pageText.pollingNoConnection })
    for (const status of [500, 429]) {
      const busy = run([{ type: 'pollFailed', failure: { kind: 'http-error', status, error: null } }], waiting)
      expect(busy).toEqual({ ...waiting, notice: pageText.pollingServerBusy })
    }
    expect(run([fetched(processing, 3)], offline)).toEqual(waiting)
  })

  it('ends the wait with a problem on any other failure and when the budget is used up', () => {
    const waiting = run([{ type: 'pollingStarted', origin: 'link' }, fetched(processing)])

    expect(run([{ type: 'pollFailed', failure: notFound }], waiting)).toEqual({
      kind: 'problem',
      message: failureMessage(notFound),
    })
    expect(run([{ type: 'pollingGaveUp', minutes: 65 }], waiting)).toEqual({
      kind: 'problem',
      message: pollingGaveUp(65),
    })
  })
})

import type { ApiFailure } from './api'
import type { ErrorCode, FailedTask, FailureCode, InputRejectedTask, InputRejectionCode, Variant } from './generated'
import type { Origin, Stage } from './taskFlowMachine'

export const pageText = {
  title: 'Image processor',
  chooseFile: 'Choose an image',
  uploading: 'Uploading…',
  loading: 'Loading…',
  processing: 'Processing…',
  uploadedWaiting: 'Uploaded. Waiting for processing to start…',
  waitingForUpload: 'Waiting for the upload or for processing to start…',
  done: 'Done.',
  original: 'Original',
  view: 'View',
  download: 'Download',
  rejected: 'The file was not accepted.',
  failed: 'Something went wrong on our side.',
  chooseAnother: 'Choose another file to try again.',
  newUpload: 'New upload',
  uploadUnconfirmed: 'Could not confirm the upload.',
  checkStatus: 'Check status',
  uploadAgain: 'Upload again',
  uploadNotStarted: 'Upload or processing did not start. Please try again.',
  invalidLink: 'This link is not valid.',
  pollingNoConnection: 'No connection to the server, trying again.',
  pollingServerBusy: 'The server could not answer, trying again.',
  configUnavailable: 'The page could not load its settings. Reload it to try again.',
}

// minutes: the retry budget, the earliest time after the first attempt at which the dead-letter function sets FAILED.
export function takingLonger(minutes: number): string {
  return `Taking longer than usual: the service may be retrying. If the image cannot be processed, the page shows an error about ${minutes} minutes after the upload, or later when the service is busy.`
}

export function pollingGaveUp(minutes: number): string {
  return `This page stopped checking the task after ${minutes} minutes. Reload it to check again.`
}

const clientText = {
  networkError: 'No connection to the server.',
  serverTrouble: 'The server could not answer. Try again.',
  unexpected: 'Unexpected response from the server',
}

const errorText: Record<ErrorCode, string> = {
  INVALID_REQUEST: 'The request was not valid.',
  UNSUPPORTED_CONTENT_TYPE: 'This type of file is not supported.',
  FILE_TOO_LARGE: 'The file is too large.',
  TASK_NOT_FOUND: 'The task does not exist.',
  NOT_FOUND: 'The page was not found.',
  METHOD_NOT_ALLOWED: 'The request was not allowed.',
  INTERNAL_ERROR: 'Something went wrong on our side. Try again.',
}

const rejectionText: Record<InputRejectionCode, string> = {
  FILE_TOO_LARGE: 'The file is too large.',
  PIXEL_LIMIT_EXCEEDED: 'The image has too many pixels.',
  CONTENT_TYPE_MISMATCH: 'The file is not the type of image it claims to be.',
  UNDECODABLE_IMAGE: 'The file could not be read as an image.',
}

const failureText: Record<FailureCode, string> = {
  PROCESSING_ERROR: 'The image could not be processed.',
  ORIGINAL_MISSING: 'The uploaded file was not found.',
}

// 429 is the Function URL throttling and 5xx a crashed or failing function: a later attempt may succeed.
export function isServerTrouble(status: number): boolean {
  return status === 429 || status >= 500
}

export function failureMessage(failure: ApiFailure): string {
  switch (failure.kind) {
    case 'network-error':
      return clientText.networkError
    case 'http-error':
      return httpErrorMessage(failure)
    default: {
      const unreachable: never = failure
      return unreachable
    }
  }
}

export function rejectedMessage(task: InputRejectedTask): string {
  return `${pageText.rejected} ${rejectionText[task.error.code]}`
}

export function failedMessage(task: FailedTask): string {
  return `${pageText.failed} ${failureText[task.error.code]} Task ${task.id}.`
}

export function variantLabel(variant: Variant): string {
  return `${variant.label} (${variant.width}×${variant.height})`
}

export function waitingMessage(origin: Origin, stage: Stage): string {
  switch (stage) {
    case 'unknown':
      return origin === 'link' ? pageText.loading : pageText.uploadedWaiting
    case 'pending':
      return origin === 'link' ? pageText.waitingForUpload : pageText.uploadedWaiting
    case 'processing':
      return pageText.processing
    default: {
      const unreachable: never = stage
      return unreachable
    }
  }
}

export function linkLabel(action: string, target: string): string {
  return `${action} ${target}`
}

function httpErrorMessage(failure: Extract<ApiFailure, { kind: 'http-error' }>): string {
  const error = failure.error
  if (error === null) {
    if (isServerTrouble(failure.status)) {
      return clientText.serverTrouble
    }
    return `${clientText.unexpected} (HTTP ${failure.status}).`
  }
  if (error.code === 'FILE_TOO_LARGE' && error.maxUploadBytes !== undefined) {
    return `${errorText.FILE_TOO_LARGE} The limit is ${error.maxUploadBytes / (1024 * 1024)} MiB.`
  }
  return errorText[error.code]
}

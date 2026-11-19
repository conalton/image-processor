import { readFileSync } from 'node:fs'
import type { Headers } from 'undici'
import { bodyText, createSend, type Send } from './client.ts'
import { readConfig, type Config } from './config.ts'
import { checkLinks } from './links.ts'
import { checkStatus, expectStatus, fail, failureMessage, pass } from './report.ts'
import { createTask, parseRecord, putOriginal, waitForTerminalState, type Fixture, type Upload } from './tasks.ts'

const PNG = 'image/png'

function fixture(name: string): Fixture {
  return { name, bytes: readFileSync(new URL(`fixtures/${name}`, import.meta.url)) }
}

function allowsOrigin(headers: Headers, origin: string): boolean {
  return headers.get('access-control-allow-origin') === origin
}

async function checkPage(send: Send, config: Config): Promise<void> {
  const page = await expectStatus('page', 200, send(config.pageUrl))
  if (!page.headers.get('content-type')?.toLowerCase().startsWith('text/html')) fail('page: the content type is not text/html')
  if (!page.headers.get('cache-control')?.toLowerCase().startsWith('no-cache')) fail('page: Cache-Control is not no-cache')
  if (!/<script type="module"[^>]*>\s*[^<\s]/.test(bodyText(page))) fail('page: no inline module script')
  pass('page script is inline')
  await expectStatus('page with a task in the address', 200, send(`${config.pageUrl}?task=00000000-0000-4000-8000-000000000000`))
}

async function checkPageConfig(send: Send, config: Config): Promise<void> {
  const reply = await expectStatus('page config', 200, send(new URL('config.json', config.pageUrl).href))
  const pageConfig = parseRecord(bodyText(reply), 'page config')
  if (pageConfig.apiUrl !== config.api) fail(`page config: apiUrl is not ${config.api}`)
  const budget = pageConfig.retryBudgetSeconds
  if (typeof budget !== 'number' || !Number.isInteger(budget) || budget <= 0) {
    fail('page config: retryBudgetSeconds is not a positive whole number')
  }
}

async function checkBucketCors(send: Send, config: Config, uploadUrl: string, signedHeaders: string[]): Promise<void> {
  // The browser sets Content-Length itself and does not list it in the preflight.
  const requested = signedHeaders.filter((name) => name !== 'content-length').sort().join(', ')
  const reply = await expectStatus(
    'CORS preflight on the images bucket',
    200,
    send(uploadUrl, {
      method: 'OPTIONS',
      headers: { origin: config.pageOrigin, 'access-control-request-method': 'PUT', 'access-control-request-headers': requested },
    }),
  )
  if (!allowsOrigin(reply.headers, config.pageOrigin)) fail('CORS preflight: the page origin is not allowed')
}

// LocalStack answers the preflight from its own origin list, not from the cors block.
async function checkFunctionUrlCors(send: Send, config: Config): Promise<void> {
  if (!config.awsOnlyChecks) {
    console.log('skipped: the CORS preflight of the Function URL (AWS_ONLY_CHECKS is not 1)')
    return
  }
  const step = 'CORS preflight on the Function URL'
  const reply = await checkStatus(
    step,
    [200, 204],
    send(`${config.api}/api/tasks`, {
      method: 'OPTIONS',
      headers: { origin: config.pageOrigin, 'access-control-request-method': 'POST', 'access-control-request-headers': 'content-type' },
    }),
  )
  pass(step)
  if (!allowsOrigin(reply.headers, config.pageOrigin)) fail(`${step}: the page origin is not allowed`)
}

async function checkRefusedUploads(send: Send, upload: Upload, png: Fixture, text: Fixture): Promise<void> {
  await expectStatus('upload with a different Content-Length is refused', 403, putOriginal(send, upload, text.bytes, 'without-length'))
  await expectStatus('upload with a different Content-Type is refused', 403, putOriginal(send, upload, png.bytes, 'wrong-type'))
  await expectStatus('upload with a different Content-Disposition is refused', 403, putOriginal(send, upload, png.bytes, 'wrong-disposition'))
}

async function checkUploadedImage(send: Send, config: Config, png: Fixture, text: Fixture): Promise<void> {
  const { id, upload } = await createTask(send, config, png, PNG)
  await checkBucketCors(send, config, upload.url, Object.keys(upload.headers))
  await checkRefusedUploads(send, upload, png, text)
  await expectStatus('upload the original', 200, putOriginal(send, upload, png.bytes, 'signed'))
  await expectStatus('second upload to the same URL is refused', 412, putOriginal(send, upload, png.bytes, 'signed'))

  const task = await waitForTerminalState(send, config, id)
  if (task.status !== 'DONE') fail(`${png.name}: expected DONE, got ${task.status} (error code: ${task.errorCode})`)
  pass('task is DONE')
  await checkLinks(send, task, png, PNG)
}

async function checkRejection(send: Send, config: Config, file: Fixture, name: string, contentType: string, errorCode: string): Promise<void> {
  const { id, upload } = await createTask(send, config, { name, bytes: file.bytes }, contentType)
  await expectStatus(`upload ${name} as ${contentType}`, 200, putOriginal(send, upload, file.bytes, 'signed'))
  const task = await waitForTerminalState(send, config, id)
  if (task.status !== 'INPUT_REJECTED') fail(`${name} as ${contentType}: expected INPUT_REJECTED, got ${task.status} (error code: ${task.errorCode})`)
  if (task.errorCode !== errorCode) fail(`${name} as ${contentType}: expected ${errorCode}, got ${task.errorCode}`)
  pass(`${name} as ${contentType} is rejected with ${errorCode}`)
}

async function main(): Promise<void> {
  const config = readConfig()
  const send = createSend(config.connectHost)
  const png = fixture('gradient.png')
  const text = fixture('not-an-image.txt')

  await checkPage(send, config)
  await checkPageConfig(send, config)
  await checkFunctionUrlCors(send, config)
  await checkUploadedImage(send, config, png, text)
  await checkRejection(send, config, text, 'not-an-image.png', PNG, 'UNDECODABLE_IMAGE')
  await checkRejection(send, config, png, 'gradient.jpg', 'image/jpeg', 'CONTENT_TYPE_MISMATCH')
  console.log('smoke test passed')
}

try {
  await main()
} catch (error) {
  console.error(`FAIL: ${failureMessage(error)}`)
  process.exitCode = 1
}

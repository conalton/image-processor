import type { Send } from './client.ts'
import { expectStatus, fail } from './report.ts'
import type { Fixture, Link, Task } from './tasks.ts'

async function checkLink(send: Send, name: string, kind: 'view' | 'download', link: Link, contentType: string): Promise<Buffer> {
  const reply = await expectStatus(`${kind} ${name}`, 200, send(kind === 'view' ? link.viewUrl : link.downloadUrl))
  if (!reply.headers.get('content-type')?.toLowerCase().startsWith(contentType.toLowerCase())) fail(`${kind} ${name}: content type is not ${contentType}`)
  if (kind === 'download' && !reply.headers.get('content-disposition')?.toLowerCase().startsWith('attachment')) {
    fail(`download ${name}: no attachment Content-Disposition`)
  }
  return reply.body
}

export async function checkLinks(send: Send, task: Task, original: Fixture, originalContentType: string): Promise<void> {
  if (task.original === undefined) fail('the task has no original')
  await checkLink(send, 'original', 'view', task.original, originalContentType)
  const downloaded = await checkLink(send, 'original', 'download', task.original, originalContentType)
  if (!downloaded.equals(original.bytes)) fail('the downloaded original differs from the uploaded file')
  if (task.variants.length === 0) fail('the task has no variants')
  for (const variant of task.variants) {
    await checkLink(send, variant.name, 'view', variant, variant.contentType)
    await checkLink(send, variant.name, 'download', variant, variant.contentType)
  }
}

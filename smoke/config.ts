import { fail } from './report.ts'

export interface Config {
  api: string
  pageUrl: string
  pageOrigin: string
  awsOnlyChecks: boolean
  connectHost: string | undefined
}

function required(name: string): string {
  const value = process.env[name]
  if (!value) fail(`${name} is not set`)
  return value
}

export function readConfig(): Config {
  const pageUrl = required('PAGE_URL')
  return {
    api: required('FUNCTION_URL').replace(/\/$/, ''),
    pageUrl,
    pageOrigin: new URL(pageUrl).origin,
    awsOnlyChecks: process.env.AWS_ONLY_CHECKS === '1',
    connectHost: process.env.CONNECT_HOST || undefined,
  }
}

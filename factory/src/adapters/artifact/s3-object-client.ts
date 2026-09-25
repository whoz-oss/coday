/**
 * Low-level S3 / MinIO object client.
 *
 * Autonomy constraint: the operational bundle may only import `node:*`. This
 * client therefore speaks the S3 REST API directly over the global `fetch` and
 * signs every request with AWS Signature Version 4 computed from
 * `node:crypto` — no AWS SDK, no `node_modules` at runtime.
 *
 * Only the handful of operations the artifact store needs are implemented:
 * `PUT` (with optional server-side copy), `GET`, `HEAD`, `DELETE` and
 * `ListObjectsV2`.
 */

import { createHash, createHmac } from 'node:crypto'

/** Connection settings for {@link S3ObjectClient}. */
export interface S3ObjectClientConfig {
  /** S3 / MinIO endpoint, e.g. `http://localhost:9000`. */
  endpoint: string
  /** Signing region, e.g. `us-east-1`. */
  region: string
  /** Target bucket. */
  bucket: string
  /** Access key id used for SigV4. */
  accessKeyId: string
  /** Secret access key used for SigV4. */
  secretAccessKey: string
  /** Optional temporary session token. */
  sessionToken?: string
  /** Injectable fetch implementation, primarily for tests. */
  fetchImpl?: typeof fetch
}

/** Response returned by {@link S3ObjectClient.getObject}. */
export interface S3ObjectResponse {
  /** Object body as an async iterable of chunks. */
  stream: AsyncIterable<Uint8Array>
}

interface S3RequestOptions {
  query?: Record<string, string>
  body?: Uint8Array
  contentType?: string
  copySource?: string
}

const SIGNING_ALGORITHM = 'AWS4-HMAC-SHA256'
const SERVICE = 's3'

function sha256Hex(data: string | Uint8Array): string {
  return createHash('sha256').update(data).digest('hex')
}

function hmac(key: Buffer | string, data: string): Buffer {
  return createHmac('sha256', key).update(data, 'utf8').digest()
}

function formatAmzDate(date: Date): { amzDate: string; dateStamp: string } {
  const amzDate = date.toISOString().replace(/[:-]|\.\d{3}/g, '')
  return { amzDate, dateStamp: amzDate.slice(0, 8) }
}

function encodeS3Component(value: string): string {
  return encodeURIComponent(value).replace(/[!'()*]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)
}

function encodeS3KeyPath(key: string): string {
  return key.split('/').map(encodeS3Component).join('/')
}

function decodeXmlEntities(value: string): string {
  return value
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&')
}

async function* iterateWebStream(body: ReadableStream<Uint8Array>): AsyncIterable<Uint8Array> {
  const reader = body.getReader()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (value) yield value
    }
  } finally {
    reader.releaseLock()
  }
}

async function* emptyStream(): AsyncIterable<Uint8Array> {
  // Intentionally empty payload.
}

/** Minimal S3 / MinIO REST client with AWS SigV4 signing. */
export class S3ObjectClient {
  readonly #config: S3ObjectClientConfig
  readonly #base: string
  readonly #host: string
  readonly #fetch: typeof fetch

  constructor(config: S3ObjectClientConfig) {
    if (!config.endpoint) throw new Error('S3ObjectClient requires an endpoint')
    if (!config.bucket) throw new Error('S3ObjectClient requires a bucket')
    this.#config = config
    this.#base = config.endpoint.replace(/\/+$/, '')
    this.#host = new URL(this.#base).host
    this.#fetch = config.fetchImpl ?? fetch
  }

  /** Stores an object at `key`. */
  async putObject(key: string, body: Uint8Array, contentType?: string): Promise<void> {
    const response = await this.#send('PUT', key, {
      body,
      ...(contentType !== undefined ? { contentType } : {}),
    })
    if (!response.ok) throw await this.#failure('PUT', key, response)
    await response.arrayBuffer()
  }

  /** Server-side copies `sourceKey` to `destinationKey`. */
  async copyObject(sourceKey: string, destinationKey: string): Promise<void> {
    const response = await this.#send('PUT', destinationKey, {
      copySource: `/${this.#config.bucket}/${encodeS3KeyPath(sourceKey)}`,
    })
    if (!response.ok) throw await this.#failure('COPY', destinationKey, response)
    await response.arrayBuffer()
  }

  /** Reads an object, or returns `null` when it does not exist. */
  async getObject(key: string): Promise<S3ObjectResponse | null> {
    const response = await this.#send('GET', key)
    if (response.status === 404) return null
    if (!response.ok) throw await this.#failure('GET', key, response)
    const body = response.body
    return { stream: body ? iterateWebStream(body) : emptyStream() }
  }

  /** Deletes an object. Returns `true` when a deletion happened. */
  async deleteObject(key: string): Promise<boolean> {
    const response = await this.#send('DELETE', key)
    if (response.status === 404) return false
    if (!response.ok && response.status !== 204) throw await this.#failure('DELETE', key, response)
    await response.arrayBuffer()
    return true
  }

  /** Returns whether an object exists. */
  async headObject(key: string): Promise<boolean> {
    const response = await this.#send('HEAD', key)
    if (response.status === 404) return false
    if (!response.ok) throw await this.#failure('HEAD', key, response)
    return true
  }

  /** Lists every object key under `prefix`, following continuation tokens. */
  async listObjectKeys(prefix: string): Promise<string[]> {
    const keys: string[] = []
    let continuationToken: string | undefined
    for (;;) {
      const query: Record<string, string> = { 'list-type': '2', prefix }
      if (continuationToken !== undefined) query['continuation-token'] = continuationToken
      const response = await this.#send('GET', '', { query })
      if (!response.ok) throw await this.#failure('LIST', prefix, response)
      const xml = await response.text()
      for (const match of xml.matchAll(/<Key>([\s\S]*?)<\/Key>/g)) {
        const key = match[1]
        if (key !== undefined) keys.push(decodeXmlEntities(key))
      }
      if (!/<IsTruncated>true<\/IsTruncated>/.test(xml)) break
      const token = xml.match(/<NextContinuationToken>([\s\S]*?)<\/NextContinuationToken>/)?.[1]
      if (token === undefined) break
      continuationToken = decodeXmlEntities(token)
    }
    return keys
  }

  async #failure(operation: string, key: string, response: Response): Promise<Error> {
    let detail = ''
    try {
      detail = (await response.text()).slice(0, 512)
    } catch {
      detail = ''
    }
    return new Error(
      `S3 ${operation} ${key || '<bucket>'} failed with status ${response.status}${detail ? `: ${detail}` : ''}`
    )
  }

  #canonicalQuery(query?: Record<string, string>): string {
    if (!query) return ''
    return Object.keys(query)
      .sort()
      .map((name) => `${encodeS3Component(name)}=${encodeS3Component(query[name] ?? '')}`)
      .join('&')
  }

  async #send(method: string, key: string, options: S3RequestOptions = {}): Promise<Response> {
    const canonicalQuery = this.#canonicalQuery(options.query)
    const canonicalUri = `/${encodeS3Component(this.#config.bucket)}${key ? `/${encodeS3KeyPath(key)}` : ''}`
    const url = `${this.#base}${canonicalUri}${canonicalQuery ? `?${canonicalQuery}` : ''}`
    const payload = options.body ?? new Uint8Array()
    const payloadHash = sha256Hex(payload)

    const headers: Record<string, string> = {
      host: this.#host,
      'x-amz-content-sha256': payloadHash,
      'x-amz-date': '',
    }
    const { amzDate, dateStamp } = formatAmzDate(new Date())
    headers['x-amz-date'] = amzDate
    if (this.#config.sessionToken !== undefined) headers['x-amz-security-token'] = this.#config.sessionToken
    if (options.contentType !== undefined) headers['content-type'] = options.contentType
    if (options.copySource !== undefined) headers['x-amz-copy-source'] = options.copySource

    const signedHeaderNames = Object.keys(headers)
      .filter((name) => name === 'host' || name.startsWith('x-amz-'))
      .sort()
    const canonicalHeaders = signedHeaderNames.map((name) => `${name}:${(headers[name] ?? '').trim()}\n`).join('')
    const signedHeaders = signedHeaderNames.join(';')
    const canonicalRequest = [method, canonicalUri, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash].join(
      '\n'
    )

    const scope = `${dateStamp}/${this.#config.region}/${SERVICE}/aws4_request`
    const stringToSign = [SIGNING_ALGORITHM, amzDate, scope, sha256Hex(canonicalRequest)].join('\n')
    const signingKey = hmac(
      hmac(hmac(hmac(`AWS4${this.#config.secretAccessKey}`, dateStamp), this.#config.region), SERVICE),
      'aws4_request'
    )
    const signature = createHmac('sha256', signingKey).update(stringToSign, 'utf8').digest('hex')

    const requestHeaders: Record<string, string> = {
      authorization: `${SIGNING_ALGORITHM} Credential=${this.#config.accessKeyId}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
      'x-amz-content-sha256': payloadHash,
      'x-amz-date': amzDate,
    }
    if (this.#config.sessionToken !== undefined) requestHeaders['x-amz-security-token'] = this.#config.sessionToken
    if (options.contentType !== undefined) requestHeaders['content-type'] = options.contentType
    if (options.copySource !== undefined) requestHeaders['x-amz-copy-source'] = options.copySource

    const init: RequestInit = { method, headers: requestHeaders }
    if (method !== 'GET' && method !== 'HEAD') init.body = Buffer.from(payload)
    return this.#fetch(url, init)
  }
}

/** Wires an S3 / MinIO object client. */
export function createS3ObjectClient(config: S3ObjectClientConfig): S3ObjectClient {
  return new S3ObjectClient(config)
}

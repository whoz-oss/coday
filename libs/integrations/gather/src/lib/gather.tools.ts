import { CodayTool } from '@coday/model'
import { AssistantToolFactory } from '@coday/model'
import { Interactor } from '@coday/model'
import { IntegrationService } from '@coday/service'
import { FunctionTool } from '@coday/model'
import * as crypto from 'crypto'
import * as fs from 'fs'

interface GatherPost {
  id: string
  title: string
  summary: string
  body: string
  tags: string[]
  author_name: string
  created: string
  score?: number
  comment_count?: number
}

interface GatherAgent {
  id: string
  name: string
  description: string
  created: string
  post_count?: number
}

interface GatherPostsResponse {
  posts: GatherPost[]
  total: number
  limit: number
  offset: number
}

interface GatherAgentsResponse {
  agents: GatherAgent[]
  total: number
  page: number
  limit: number
}

export class GatherTools extends AssistantToolFactory {
  name = 'GATHER'

  constructor(
    interactor: Interactor,
    private readonly integrationService: IntegrationService
  ) {
    super(interactor)
  }

  protected async buildTools(): Promise<CodayTool[]> {
    const result: CodayTool[] = []
    if (!this.integrationService.hasIntegration(this.name)) {
      return result
    }

    const publicKeyPem = this.integrationService.getApiKey(this.name)
    if (!publicKeyPem) {
      return result
    }

    const baseUrl = 'https://gather.is'
    let token: string | null = null
    let tokenExpiry = 0

    // Authenticate via Ed25519 challenge-response
    const authenticate = async (): Promise<string> => {
      if (token && Date.now() < tokenExpiry) {
        return token
      }

      const privateKeyPath = process.env['GATHER_PRIVATE_KEY_PATH']
      if (!privateKeyPath) {
        throw new Error('GATHER_PRIVATE_KEY_PATH environment variable not set')
      }

      const privateKeyPem = fs.readFileSync(privateKeyPath, 'utf-8')

      // Request challenge
      const challengeRes = await fetch(`${baseUrl}/api/agents/challenge`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ public_key: publicKeyPem }),
      })

      if (!challengeRes.ok) {
        throw new Error(`Challenge request failed: ${challengeRes.status}`)
      }

      const { nonce } = (await challengeRes.json()) as { nonce: string }

      // Sign the nonce
      const privateKey = crypto.createPrivateKey(privateKeyPem)
      const signature = crypto.sign(null, Buffer.from(nonce), privateKey)
      const signatureB64 = signature.toString('base64')

      // Authenticate
      const authRes = await fetch(`${baseUrl}/api/agents/authenticate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          public_key: publicKeyPem,
          nonce,
          signature: signatureB64,
        }),
      })

      if (!authRes.ok) {
        throw new Error(`Authentication failed: ${authRes.status}`)
      }

      const { token: jwt } = (await authRes.json()) as { token: string }
      token = jwt
      tokenExpiry = Date.now() + 50 * 60 * 1000 // 50 minutes
      return token
    }

    // Solve proof-of-work
    const solvePoW = async (jwt: string): Promise<{ challenge: string; nonce: string }> => {
      const powRes = await fetch(`${baseUrl}/api/pow/challenge?purpose=post`, {
        headers: { Authorization: `Bearer ${jwt}` },
      })

      if (!powRes.ok) {
        throw new Error(`PoW challenge request failed: ${powRes.status}`)
      }

      const { challenge, difficulty } = (await powRes.json()) as {
        challenge: string
        difficulty: number
      }

      // Brute-force find nonce where SHA256(challenge:nonce) has leading zero bits
      for (let i = 0; i < 10_000_000; i++) {
        const attempt = String(i)
        const hash = crypto.createHash('sha256').update(`${challenge}:${attempt}`).digest()

        let zeroBits = 0
        for (const byte of hash) {
          if (byte === 0) {
            zeroBits += 8
          } else {
            zeroBits += Math.clz32(byte) - 24
            break
          }
        }

        if (zeroBits >= difficulty) {
          return { challenge, nonce: attempt }
        }
      }

      throw new Error(`Could not solve PoW after 10M attempts (difficulty: ${difficulty})`)
    }

    // Tool 1: Read feed
    const readFeedFunction: FunctionTool<{ limit?: number; sort?: string; offset?: number }> = {
      type: 'function',
      function: {
        name: 'gather_read_feed',
        description:
          'Read recent posts from the Gather.is feed — a social platform for AI agents. Returns posts with title, summary, tags, author, and engagement stats.',
        parameters: {
          type: 'object',
          properties: {
            limit: {
              type: 'number',
              description: 'Number of posts to return (default: 25, max: 50)',
            },
            sort: {
              type: 'string',
              description: 'Sort order: "recent" or "hot" (default: "recent")',
            },
            offset: {
              type: 'number',
              description: 'Offset for pagination (default: 0)',
            },
          },
        },
        parse: JSON.parse,
        function: async (params: { limit?: number; sort?: string; offset?: number }): Promise<string> => {
          try {
            const limit = Math.min(params.limit || 25, 50)
            const sort = params.sort || 'recent'
            const offset = params.offset || 0

            const res = await fetch(
              `${baseUrl}/api/posts?limit=${limit}&sort=${sort}&offset=${offset}`
            )

            if (!res.ok) {
              throw new Error(`Feed request failed: ${res.status}`)
            }

            const data = (await res.json()) as GatherPostsResponse
            const posts = data.posts.map((p) => ({
              id: p.id,
              title: p.title,
              summary: p.summary,
              tags: p.tags,
              author: p.author_name,
              created: p.created,
              score: p.score || 0,
              comments: p.comment_count || 0,
            }))

            return JSON.stringify({ posts, total: data.total }, null, 2)
          } catch (error) {
            return `Error reading feed: ${error instanceof Error ? error.message : String(error)}`
          }
        },
      },
    }

    // Tool 2: Post content
    const postFunction: FunctionTool<{
      title: string
      summary: string
      body: string
      tags: string[]
    }> = {
      type: 'function',
      function: {
        name: 'gather_post',
        description:
          'Post content to the Gather.is feed. Requires Ed25519 authentication and solves proof-of-work automatically. All fields are required.',
        parameters: {
          type: 'object',
          properties: {
            title: {
              type: 'string',
              description: 'Post title (max 200 characters). REQUIRED.',
            },
            summary: {
              type: 'string',
              description:
                'Short summary for the feed (max 500 characters). This is what agents see when scanning. REQUIRED.',
            },
            body: {
              type: 'string',
              description: 'Full post body (max 10000 characters). REQUIRED.',
            },
            tags: {
              type: 'array',
              items: { type: 'string' },
              description: 'Tags for the post (1-5 tags). REQUIRED.',
            },
          },
        },
        parse: JSON.parse,
        function: async (params: {
          title: string
          summary: string
          body: string
          tags: string[]
        }): Promise<string> => {
          try {
            if (!params.title || !params.summary || !params.body || !params.tags?.length) {
              return 'Error: title, summary, body, and tags are all required.'
            }

            const jwt = await authenticate()
            const pow = await solvePoW(jwt)

            const res = await fetch(`${baseUrl}/api/posts`, {
              method: 'POST',
              headers: {
                Authorization: `Bearer ${jwt}`,
                'Content-Type': 'application/json',
              },
              body: JSON.stringify({
                title: params.title,
                summary: params.summary,
                body: params.body,
                tags: params.tags,
                pow_challenge: pow.challenge,
                pow_nonce: pow.nonce,
              }),
            })

            if (!res.ok) {
              const errorText = await res.text()
              throw new Error(`Post failed (${res.status}): ${errorText}`)
            }

            const post = (await res.json()) as GatherPost
            return JSON.stringify({
              success: true,
              id: post.id,
              title: post.title,
              message: 'Post published successfully on Gather.is',
            })
          } catch (error) {
            return `Error posting: ${error instanceof Error ? error.message : String(error)}`
          }
        },
      },
    }

    // Tool 3: Discover agents
    const discoverAgentsFunction: FunctionTool<{ limit?: number; page?: number }> = {
      type: 'function',
      function: {
        name: 'gather_discover_agents',
        description:
          'Discover other AI agents on the Gather.is platform. Returns agent names, descriptions, and activity stats.',
        parameters: {
          type: 'object',
          properties: {
            limit: {
              type: 'number',
              description: 'Number of agents to return (default: 25)',
            },
            page: {
              type: 'number',
              description: 'Page number for pagination (default: 1)',
            },
          },
        },
        parse: JSON.parse,
        function: async (params: { limit?: number; page?: number }): Promise<string> => {
          try {
            const limit = params.limit || 25
            const page = params.page || 1

            const res = await fetch(
              `${baseUrl}/api/agents?limit=${limit}&page=${page}`
            )

            if (!res.ok) {
              throw new Error(`Agents request failed: ${res.status}`)
            }

            const data = (await res.json()) as GatherAgentsResponse
            const agents = data.agents.map((a) => ({
              id: a.id,
              name: a.name,
              description: a.description,
              joined: a.created,
              posts: a.post_count || 0,
            }))

            return JSON.stringify({ agents, total: data.total }, null, 2)
          } catch (error) {
            return `Error discovering agents: ${error instanceof Error ? error.message : String(error)}`
          }
        },
      },
    }

    result.push(readFeedFunction, postFunction, discoverAgentsFunction)

    return result
  }
}

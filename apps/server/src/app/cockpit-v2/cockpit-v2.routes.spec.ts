import express from 'express'
import {
  COCKPIT_V2_ROUTE_BASE,
  buildCockpitV2ServiceInfo,
  buildCockpitV2Status,
  registerCockpitV2Routes,
} from './cockpit-v2.routes'

describe('cockpit-v2.routes', () => {
  describe('buildCockpitV2ServiceInfo', () => {
    it('returns the cockpit-v2 service descriptor with the provided version', () => {
      const info = buildCockpitV2ServiceInfo('2.0.0')

      expect(info).toEqual({
        service: 'cockpit-v2',
        status: 'ok',
        version: '2.0.0',
        endpoints: [`GET ${COCKPIT_V2_ROUTE_BASE}`, `GET ${COCKPIT_V2_ROUTE_BASE}/status`],
      })
    })

    it('falls back to the runtime version when none is provided', () => {
      const info = buildCockpitV2ServiceInfo()
      expect(typeof info.version).toBe('string')
      expect(info.version.length).toBeGreaterThan(0)
    })
  })

  describe('buildCockpitV2Status', () => {
    it('returns an ok status payload with a deterministic timestamp', () => {
      const now = new Date('2026-01-02T03:04:05.000Z')
      const status = buildCockpitV2Status('2.0.0', now)

      expect(status).toEqual({
        service: 'cockpit-v2',
        status: 'ok',
        version: '2.0.0',
        timestamp: '2026-01-02T03:04:05.000Z',
      })
    })
  })

  describe('registerCockpitV2Routes', () => {
    it('registers both the service info and status routes', () => {
      const app = express()
      const registeredPaths: string[] = []
      const fakeApp = {
        get: (path: string) => {
          registeredPaths.push(path)
        },
      } as unknown as express.Application

      registerCockpitV2Routes(fakeApp)

      expect(registeredPaths).toContain(COCKPIT_V2_ROUTE_BASE)
      expect(registeredPaths).toContain(`${COCKPIT_V2_ROUTE_BASE}/status`)
      expect(app).toBeDefined()
    })

    it('invokes the username resolver when provided', () => {
      const calledWith: express.Request[] = []
      const getUsername = (req: express.Request): string => {
        calledWith.push(req)
        return 'alice@example.com'
      }
      let infoHandler: express.RequestHandler | undefined

      const fakeApp = {
        get: (path: string, handler: express.RequestHandler) => {
          if (path === COCKPIT_V2_ROUTE_BASE) {
            infoHandler = handler
          }
        },
      } as unknown as express.Application

      registerCockpitV2Routes(fakeApp, getUsername)

      let statusCode: number | undefined
      let payload: unknown
      const response = {
        status: (code: number) => {
          statusCode = code
          return {
            json: (body: unknown) => {
              payload = body
            },
          }
        },
      }
      const request = {} as express.Request
      infoHandler?.(request, response as unknown as express.Response, (() => undefined) as express.NextFunction)

      expect(calledWith).toEqual([request])
      expect(statusCode).toBe(200)
      expect(payload).toEqual(expect.objectContaining({ service: 'cockpit-v2', status: 'ok' }))
    })
  })
})

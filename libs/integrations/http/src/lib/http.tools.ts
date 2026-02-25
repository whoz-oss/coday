/**
 * HTTP integration with OAuth2 support.
 *
 * Currently hard-coded to expose Google Calendar tools while the generic
 * HTTP integration pattern is validated. Future iterations will make the
 * endpoints declaratively configurable.
 *
 * Configuration expected in coday.yaml:
 *
 *   integration:
 *     MY_CALENDAR:
 *       type: http
 *       oauth2:
 *         client_id: "your-google-client-id"
 *         client_secret: "your-google-client-secret"
 *         redirect_uri: "http://localhost:3000/oauth/callback"
 *
 * The integration name (MY_CALENDAR) is used as the OAuth integrationName
 * for routing callbacks, so it must be unique per project.
 */
import { AssistantToolFactory } from '@coday/model'
import { CodayTool, FunctionTool } from '@coday/model'
import { CommandContext } from '@coday/model'
import { Interactor } from '@coday/model'
import { IntegrationConfig } from '@coday/model'
import { OAuthCallbackEvent } from '@coday/model'
import { UserService } from '@coday/service'
import { GenericOAuth } from './generic-oauth'

// Google Calendar hard-coded values (temporary, for reference while validating the pattern)
// authorization_endpoint: 'https://accounts.google.com/o/oauth2/v2/auth'
// token_endpoint: 'https://oauth2.googleapis.com/token'
// scope: 'https://www.googleapis.com/auth/calendar.readonly'
// http.baseUrl: 'https://www.googleapis.com/calendar/v3'

export class HttpTools extends AssistantToolFactory {
  static readonly TYPE = 'HTTP' as const

  private oauth: GenericOAuth | null = null

  constructor(
    interactor: Interactor,
    private readonly userService: UserService,
    instanceName: string,
    config?: IntegrationConfig
  ) {
    super(interactor, instanceName, config)
  }

  async handleOAuthCallback(event: OAuthCallbackEvent): Promise<void> {
    if (this.oauth) {
      await this.oauth.handleCallback(event)
    }
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    const oauth2Config = this.config?.oauth2
    if (!oauth2Config?.client_id || !oauth2Config?.client_secret || !oauth2Config?.redirect_uri) {
      this.interactor.debug(
        `HTTP integration '${this.name}' requires oauth2.client_id, oauth2.client_secret and oauth2.redirect_uri`
      )
      return result
    }

    const projectName = context.project.name

    const baseUrl = this.config?.http?.baseUrl
    if (!baseUrl) {
      this.interactor.debug(`HTTP integration '${this.name}' requires http.baseUrl`)
      return result
    }

    if (!oauth2Config.authorization_endpoint || !oauth2Config.token_endpoint) {
      this.interactor.debug(
        `HTTP integration '${this.name}' requires oauth2.authorization_endpoint and oauth2.token_endpoint`
      )
      return result
    }

    this.oauth = new GenericOAuth(
      {
        clientId: oauth2Config.client_id,
        clientSecret: oauth2Config.client_secret,
        redirectUri: oauth2Config.redirect_uri,
        authorizationEndpoint: oauth2Config.authorization_endpoint,
        tokenEndpoint: oauth2Config.token_endpoint,
        scope: oauth2Config.scope,
      },
      this.interactor,
      this.userService,
      projectName,
      this.name
    )

    const getEventsTool: FunctionTool<{
      calendarId?: string
      timeMin?: string
      timeMax?: string
      maxResults?: number
    }> = {
      type: 'function',
      function: {
        name: `${this.name}__getEvents`,
        description:
          'List events from a Google Calendar. Will prompt for OAuth authentication if not already connected.',
        parameters: {
          type: 'object',
          properties: {
            calendarId: {
              type: 'string',
              description:
                'Calendar ID to query. Use "primary" for the main calendar (default). ' +
                'Other calendars use their email-like ID.',
            },
            timeMin: {
              type: 'string',
              description:
                'Lower bound for events start time, ISO 8601 format (e.g. 2024-01-15T00:00:00Z). ' +
                'Defaults to now if omitted.',
            },
            timeMax: {
              type: 'string',
              description: 'Upper bound for events start time, ISO 8601 format (e.g. 2024-01-22T00:00:00Z).',
            },
            maxResults: {
              type: 'number',
              description: 'Maximum number of events to return (default: 10, max: 250).',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ calendarId = 'primary', timeMin, timeMax, maxResults = 10 }) => {
          const accessToken = await this.oauth!.authenticate()
            .then(() => this.oauth!.getAccessToken())
            .catch((err) => {
              throw new Error(`Authentication failed: ${err.message}`)
            })

          const url = new URL(`${baseUrl}/calendars/${encodeURIComponent(calendarId)}/events`)
          url.searchParams.set('maxResults', String(Math.min(maxResults, 250)))
          url.searchParams.set('singleEvents', 'true')
          url.searchParams.set('orderBy', 'startTime')
          if (timeMin) url.searchParams.set('timeMin', timeMin)
          if (timeMax) url.searchParams.set('timeMax', timeMax)

          const response = await fetch(url.toString(), {
            headers: { Authorization: `Bearer ${accessToken}` },
          })

          if (!response.ok) {
            const errorBody = await response.text()
            throw new Error(`Google Calendar API error ${response.status}: ${errorBody}`)
          }

          const data = (await response.json()) as any
          const events = (data.items ?? []).map((event: any) => ({
            id: event.id,
            summary: event.summary ?? '(no title)',
            start: event.start?.dateTime ?? event.start?.date,
            end: event.end?.dateTime ?? event.end?.date,
            location: event.location,
            description: event.description,
            status: event.status,
            htmlLink: event.htmlLink,
          }))

          return {
            calendarId,
            totalEvents: events.length,
            events,
          }
        },
      },
    }

    result.push(getEventsTool)
    return result
  }
}

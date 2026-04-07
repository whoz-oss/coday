import {
  AssistantToolFactory,
  CodayTool,
  CommandContext,
  FunctionTool,
  IntegrationConfig,
  Interactor,
  OAuthCallbackEvent,
} from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { GenericOAuth } from '@coday/integrations-http'
import { GoogleDriveConfig } from './google-drive.types'
import { GoogleDriveClient } from './google-drive.client'
import { GoogleDriveWhitelist } from './google-drive-whitelist'

export class GoogleDriveTools extends AssistantToolFactory {
  static readonly TYPE = 'GOOGLE_DRIVE' as const

  private oauth: GenericOAuth | null = null

  constructor(
    interactor: Interactor,
    private readonly services: CodayServices,
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

  protected async buildTools(context: CommandContext): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    if (!this.services.integration.hasIntegration(this.name)) {
      return result
    }

    const integrationConfig = this.services.integration.getIntegration(this.name)
    if (!integrationConfig) {
      return result
    }

    const driveConfig = integrationConfig.drive as GoogleDriveConfig | undefined
    if (!driveConfig?.allowedPaths?.length) {
      this.interactor.debug(`[GoogleDrive:${this.name}] no allowedPaths configured, denying all access`)
      return result
    }

    const oauth2Config = integrationConfig.oauth2
    if (
      !oauth2Config?.client_id ||
      !oauth2Config?.client_secret ||
      !oauth2Config?.redirect_uri ||
      !oauth2Config?.authorization_endpoint ||
      !oauth2Config?.token_endpoint
    ) {
      this.interactor.debug(`[GoogleDrive:${this.name}] missing oauth2 configuration`)
      return result
    }

    if (!this.oauth) {
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
        this.services.user,
        context.project.name,
        this.name
      )
    }

    const getAccessToken = async (): Promise<string> => {
      if (this.oauth!.isAuthenticated() || this.oauth!.hasRefreshToken()) {
        return this.oauth!.getAccessToken()
      }
      await this.oauth!.authenticate()
      return this.oauth!.getAccessToken()
    }

    const client = new GoogleDriveClient(getAccessToken)
    const whitelist = new GoogleDriveWhitelist(client, driveConfig.allowedPaths)

    const allowedFolderEntries = whitelist.getAllowedFolderEntries()
    const allowedFolderNames = allowedFolderEntries.map((e) => e.name).join(', ')

    // Tool 1: list_files
    const listFilesTool: FunctionTool<{ folderId?: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__list_files`,
        description:
          `List files in a Google Drive folder. Access is restricted to project-allowed folders: ${allowedFolderNames}. ` +
          `If no folderId is provided, returns the list of allowed root folders. ` +
          `Provide a folderId to list its contents.`,
        parameters: {
          type: 'object',
          properties: {
            folderId: {
              type: 'string',
              description: 'ID of the folder to list. If omitted, returns the allowed root folders.',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ folderId }: { folderId?: string }): Promise<unknown> => {
          if (!folderId) {
            return allowedFolderEntries.map((e) => ({ id: e.id, name: e.name, type: 'folder' }))
          }

          const allowed = await whitelist.isAllowedFolder(folderId)
          if (!allowed) {
            return `Access denied: folder '${folderId}' is not in the allowed paths.`
          }

          try {
            const driveId = await whitelist.getDriveIdForFolder(folderId)
            const response = await client.listFiles(folderId, driveId)
            return response.files.map((f) => ({
              id: f.id,
              name: f.name,
              mimeType: f.mimeType,
              modifiedTime: f.modifiedTime,
              size: f.size,
            }))
          } catch (err) {
            return `Error listing files: ${err instanceof Error ? err.message : String(err)}`
          }
        },
      },
    }

    result.push(listFilesTool)

    // Tool 2: read_file
    const readFileTool: FunctionTool<{ fileId: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__read_file`,
        description:
          `Read the content of a Google Drive file. Access is restricted to project-allowed folders: ${allowedFolderNames}. ` +
          `Google Docs are exported as plain text, Sheets as CSV, other files as raw content.`,
        parameters: {
          type: 'object',
          properties: {
            fileId: {
              type: 'string',
              description: 'ID of the file to read. Required.',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ fileId }: { fileId: string }): Promise<unknown> => {
          const allowed = await whitelist.isAllowedFile(fileId)
          if (!allowed) {
            return `Access denied: file '${fileId}' is not in the allowed paths.`
          }

          try {
            const file = await client.getFile(fileId, true)
            const content = await client.readFileContent(fileId, file.mimeType)
            return { name: file.name, mimeType: file.mimeType, content }
          } catch (err) {
            return `Error reading file: ${err instanceof Error ? err.message : String(err)}`
          }
        },
      },
    }

    result.push(readFileTool)

    // Tool 3: search_files
    const searchFilesTool: FunctionTool<{ query: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__search_files`,
        description:
          `Search for files in Google Drive using full-text search. Access is restricted to project-allowed folders: ${allowedFolderNames}. ` +
          `Results are scoped to the allowed folder IDs.`,
        parameters: {
          type: 'object',
          properties: {
            query: {
              type: 'string',
              description: 'Search query string (full-text search across file names and content). Required.',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ query }: { query: string }): Promise<unknown> => {
          try {
            const folderEntries = whitelist.getAllowedFolderEntries()
            if (!folderEntries.length) {
              return 'No allowed folders configured for search.'
            }

            const folderIds = folderEntries.map((e) => e.id)
            const driveId = folderEntries[0]?.driveId
            const response = await client.searchFiles(query, folderIds, driveId)
            return response.files.map((f) => ({
              id: f.id,
              name: f.name,
              mimeType: f.mimeType,
              modifiedTime: f.modifiedTime,
              size: f.size,
            }))
          } catch (err) {
            return `Error searching files: ${err instanceof Error ? err.message : String(err)}`
          }
        },
      },
    }

    result.push(searchFilesTool)

    // Tool 4: get_file_metadata
    const getFileMetadataTool: FunctionTool<{ fileId: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__get_file_metadata`,
        description: `Get metadata for a Google Drive file (name, type, modified date, size). Access is restricted to project-allowed folders: ${allowedFolderNames}.`,
        parameters: {
          type: 'object',
          properties: {
            fileId: {
              type: 'string',
              description: 'ID of the file to retrieve metadata for. Required.',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ fileId }: { fileId: string }): Promise<unknown> => {
          const allowed = await whitelist.isAllowedFile(fileId)
          if (!allowed) {
            return `Access denied: file '${fileId}' is not in the allowed paths.`
          }

          try {
            const file = await client.getFile(fileId, true)
            return {
              id: file.id,
              name: file.name,
              mimeType: file.mimeType,
              modifiedTime: file.modifiedTime,
              size: file.size,
            }
          } catch (err) {
            return `Error getting file metadata: ${err instanceof Error ? err.message : String(err)}`
          }
        },
      },
    }

    result.push(getFileMetadataTool)

    return result
  }
}

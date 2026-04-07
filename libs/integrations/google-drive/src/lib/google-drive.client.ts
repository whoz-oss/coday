export type DriveFile = {
  id: string
  name: string
  mimeType: string
  modifiedTime?: string
  size?: string
  parents?: string[]
}

export type DriveListResponse = {
  files: DriveFile[]
  nextPageToken?: string
}

const DRIVE_API_BASE = 'https://www.googleapis.com/drive/v3'

export class GoogleDriveClient {
  constructor(private readonly getAccessToken: () => Promise<string>) {}

  private async request<T>(url: string, options?: RequestInit): Promise<T> {
    const accessToken = await this.getAccessToken()
    const response = await fetch(url, {
      ...options,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        ...options?.headers,
      },
    })

    if (!response.ok) {
      const errorBody = await response.text()
      throw new Error(`Google Drive API error ${response.status}: ${errorBody}`)
    }

    return response.json() as Promise<T>
  }

  async listFiles(folderId: string, driveId?: string, pageToken?: string): Promise<DriveListResponse> {
    const params = new URLSearchParams()
    const isRootOfSharedDrive = driveId && folderId === driveId

    if (isRootOfSharedDrive) {
      params.set('corpora', 'drive')
      params.set('driveId', driveId)
    } else {
      params.set('q', `'${folderId}' in parents and trashed = false`)
    }

    if (driveId) {
      params.set('supportsAllDrives', 'true')
      params.set('includeItemsFromAllDrives', 'true')
    }

    params.set('fields', 'files(id,name,mimeType,modifiedTime,size,parents),nextPageToken')
    params.set('pageSize', '100')

    if (pageToken) {
      params.set('pageToken', pageToken)
    }

    return this.request<DriveListResponse>(`${DRIVE_API_BASE}/files?${params.toString()}`)
  }

  async getFile(fileId: string, supportsAllDrives?: boolean): Promise<DriveFile> {
    const params = new URLSearchParams()
    params.set('fields', 'id,name,mimeType,modifiedTime,size,parents')
    if (supportsAllDrives) {
      params.set('supportsAllDrives', 'true')
    }
    return this.request<DriveFile>(`${DRIVE_API_BASE}/files/${fileId}?${params.toString()}`)
  }

  async readFileContent(fileId: string, mimeType?: string): Promise<string> {
    const accessToken = await this.getAccessToken()

    // Google Workspace types need export
    if (mimeType === 'application/vnd.google-apps.document') {
      const params = new URLSearchParams({ mimeType: 'text/plain' })
      const response = await fetch(`${DRIVE_API_BASE}/files/${fileId}/export?${params.toString()}`, {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!response.ok) {
        const errorBody = await response.text()
        throw new Error(`Google Drive export error ${response.status}: ${errorBody}`)
      }
      return response.text()
    }

    if (mimeType === 'application/vnd.google-apps.spreadsheet') {
      const params = new URLSearchParams({ mimeType: 'text/csv' })
      const response = await fetch(`${DRIVE_API_BASE}/files/${fileId}/export?${params.toString()}`, {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      if (!response.ok) {
        const errorBody = await response.text()
        throw new Error(`Google Drive export error ${response.status}: ${errorBody}`)
      }
      return response.text()
    }

    // Regular file download
    const params = new URLSearchParams({ alt: 'media' })
    const response = await fetch(`${DRIVE_API_BASE}/files/${fileId}?${params.toString()}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    if (!response.ok) {
      const errorBody = await response.text()
      throw new Error(`Google Drive download error ${response.status}: ${errorBody}`)
    }
    return response.text()
  }

  async searchFiles(
    query: string,
    folderIds: string[],
    driveId?: string,
    pageToken?: string
  ): Promise<DriveListResponse> {
    const params = new URLSearchParams()
    const folderConstraints = folderIds.map((id) => `'${id}' in parents`).join(' or ')
    const fullQuery = `fullText contains '${query}' and (${folderConstraints}) and trashed = false`
    params.set('q', fullQuery)

    if (driveId) {
      params.set('supportsAllDrives', 'true')
      params.set('includeItemsFromAllDrives', 'true')
    }

    params.set('fields', 'files(id,name,mimeType,modifiedTime,size,parents),nextPageToken')
    params.set('pageSize', '50')

    if (pageToken) {
      params.set('pageToken', pageToken)
    }

    return this.request<DriveListResponse>(`${DRIVE_API_BASE}/files?${params.toString()}`)
  }

  async listChildren(folderId: string, driveId?: string): Promise<DriveFile[]> {
    const allFiles: DriveFile[] = []
    let pageToken: string | undefined

    do {
      const response = await this.listFiles(folderId, driveId, pageToken)
      allFiles.push(...response.files)
      pageToken = response.nextPageToken
    } while (pageToken)

    return allFiles
  }
}

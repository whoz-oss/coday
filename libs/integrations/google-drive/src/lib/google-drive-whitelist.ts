import { GoogleDriveAllowedPath } from './google-drive.types'
import { GoogleDriveClient } from './google-drive.client'

export type AllowedFolderEntry = {
  id: string
  name: string
  driveId?: string
}

export class GoogleDriveWhitelist {
  private allowedFolderIds: Set<string> | null = null
  private allowedFileIds: Set<string> | null = null
  private folderToDriveId: Map<string, string> = new Map()
  private resolving: Promise<void> | null = null

  constructor(
    private readonly client: GoogleDriveClient,
    private readonly allowedPaths: GoogleDriveAllowedPath[]
  ) {}

  private async resolve(): Promise<void> {
    if (this.allowedFolderIds !== null) return
    if (this.resolving) {
      await this.resolving
      return
    }

    this.resolving = this.doResolve()
    await this.resolving
    this.resolving = null
  }

  private async doResolve(): Promise<void> {
    const folderIds = new Set<string>()
    const fileIds = new Set<string>()

    for (const path of this.allowedPaths) {
      if (path.fileId) {
        fileIds.add(path.fileId)
      }

      if (path.folderId) {
        folderIds.add(path.folderId)
        if (path.driveId) {
          this.folderToDriveId.set(path.folderId, path.driveId)
        }

        // Recursively expand subfolders unless recursive is explicitly false
        const recursive = path.recursive !== false
        if (recursive) {
          await this.expandFolder(path.folderId, path.driveId, folderIds)
        }
      }

      if (path.driveId && !path.folderId) {
        // Shared drive root: treat driveId as folderId
        folderIds.add(path.driveId)
        this.folderToDriveId.set(path.driveId, path.driveId)
        const recursive = path.recursive !== false
        if (recursive) {
          await this.expandFolder(path.driveId, path.driveId, folderIds)
        }
      }
    }

    this.allowedFolderIds = folderIds
    this.allowedFileIds = fileIds
  }

  private async expandFolder(folderId: string, driveId: string | undefined, collected: Set<string>): Promise<void> {
    try {
      const children = await this.client.listChildren(folderId, driveId)
      for (const child of children) {
        if (child.mimeType === 'application/vnd.google-apps.folder') {
          if (!collected.has(child.id)) {
            collected.add(child.id)
            if (driveId) {
              this.folderToDriveId.set(child.id, driveId)
            }
            await this.expandFolder(child.id, driveId, collected)
          }
        }
      }
    } catch (_err) {
      // Silently skip folders we cannot access
    }
  }

  async isAllowedFolder(folderId: string): Promise<boolean> {
    await this.resolve()
    return this.allowedFolderIds!.has(folderId)
  }

  async isAllowedFile(fileId: string): Promise<boolean> {
    await this.resolve()
    if (this.allowedFileIds!.has(fileId)) return true

    // Check if file is in an allowed folder by fetching its parents
    try {
      const file = await this.client.getFile(fileId, true)
      if (file.parents) {
        for (const parentId of file.parents) {
          if (this.allowedFolderIds!.has(parentId)) return true
        }
      }
    } catch (_err) {
      // Cannot access file metadata
    }
    return false
  }

  getAllowedFolderEntries(): AllowedFolderEntry[] {
    return this.allowedPaths
      .filter((p) => p.folderId || p.driveId)
      .map((p) => ({
        id: p.folderId ?? p.driveId!,
        name: p.name,
        driveId: p.driveId,
      }))
  }

  getAllowedFileEntries(): Array<{ id: string; name: string }> {
    return this.allowedPaths
      .filter((p) => p.fileId)
      .map((p) => ({
        id: p.fileId!,
        name: p.name,
      }))
  }

  async getDriveIdForFolder(folderId: string): Promise<string | undefined> {
    await this.resolve()
    return this.folderToDriveId.get(folderId)
  }
}

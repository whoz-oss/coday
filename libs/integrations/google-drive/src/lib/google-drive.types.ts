export type GoogleDriveConfig = {
  allowedPaths: GoogleDriveAllowedPath[]
}

export type GoogleDriveAllowedPath = {
  folderId?: string // Folder in personal Drive or within a Shared Drive
  fileId?: string // Specific file (any drive)
  driveId?: string // Shared Drive ID — enables shared drive API params
  name: string // Human-readable label
  recursive?: boolean // Include subfolders (default: true)
}

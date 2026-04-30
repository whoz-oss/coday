import { inject, Injectable } from '@angular/core'
import { MatDialog } from '@angular/material/dialog'
import { ConfigApiService } from './config-api.service'
import { JsonEditorComponent, JsonEditorData } from '../../components/json-editor/json-editor.component'

@Injectable({
  providedIn: 'root',
})
export class ConfigStateService {
  private readonly configApi = inject(ConfigApiService)
  private readonly dialog = inject(MatDialog)

  /**
   * Open the user configuration editor dialog.
   * Fetches the current config, opens JsonEditorComponent, and saves on close.
   */
  openUserConfigEditor(): void {
    this.configApi.getUserConfig().subscribe({
      next: (config) => {
        const dialogRef = this.dialog.open<JsonEditorComponent, JsonEditorData, any>(JsonEditorComponent, {
          data: {
            configType: 'user',
            initialContent: JSON.stringify(config, null, 2),
          },
        })
        dialogRef.afterClosed().subscribe((result) => {
          if (result) {
            this.configApi.updateUserConfig(result).subscribe({
              error: (err) => console.error('[CONFIG-STATE] Error saving user config:', err),
            })
          }
        })
      },
      error: (err) => console.error('[CONFIG-STATE] Error loading user config:', err),
    })
  }
}

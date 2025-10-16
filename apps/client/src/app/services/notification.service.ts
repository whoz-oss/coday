import { Injectable, inject } from '@angular/core'
import { MatSnackBar } from '@angular/material/snack-bar'

@Injectable({
  providedIn: 'root',
})
export class NotificationService {
  private snackBar = inject(MatSnackBar)

  /**
   * Show a success notification
   */
  success(message: string, duration = 2000): void {
    this.snackBar.open(message, '', {
      duration,
      horizontalPosition: 'center',
      verticalPosition: 'bottom',
      panelClass: ['snackbar-success'],
    })
  }

  /**
   * Show an error notification
   */
  error(message: string, duration = 3000): void {
    this.snackBar.open(message, '', {
      duration,
      horizontalPosition: 'center',
      verticalPosition: 'bottom',
      panelClass: ['snackbar-error'],
    })
  }

  /**
   * Show an info notification
   */
  info(message: string, duration = 2000): void {
    this.snackBar.open(message, '', {
      duration,
      horizontalPosition: 'center',
      verticalPosition: 'bottom',
      panelClass: ['snackbar-info'],
    })
  }
}

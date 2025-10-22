import { Injectable, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'
import { tap } from 'rxjs/operators'

/**
 * Service for managing configuration via REST API
 * Handles user and project configuration CRUD operations
 */
@Injectable({
  providedIn: 'root',
})
export class ConfigApiService {
  private http = inject(HttpClient)

  /**
   * Get user configuration
   */
  getUserConfig(): Observable<any> {
    return this.http
      .get('/api/config/user')
      .pipe(tap((config) => console.log('[CONFIG-API] User config retrieved:', config)))
  }

  /**
   * Update user configuration
   */
  updateUserConfig(config: any): Observable<{ success: boolean; message?: string; error?: string }> {
    return this.http
      .put<{ success: boolean; message?: string; error?: string }>('/api/config/user', config)
      .pipe(tap((response) => console.log('[CONFIG-API] User config updated:', response)))
  }
}

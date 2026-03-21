import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

export interface UserListItem {
  username: string
}

@Injectable({ providedIn: 'root' })
export class UserApiService {
  private readonly http = inject(HttpClient)

  /**
   * List all known users.
   */
  listUsers(): Observable<UserListItem[]> {
    return this.http.get<UserListItem[]>('/api/users')
  }
}

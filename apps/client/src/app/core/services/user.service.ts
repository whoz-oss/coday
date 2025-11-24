import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { BehaviorSubject, Observable } from 'rxjs'
import { tap } from 'rxjs/operators'

/**
 * User information response
 */
export interface UserInfo {
  username: string
}

/**
 * Service for managing current user information
 */
@Injectable({
  providedIn: 'root',
})
export class UserService {
  private readonly http = inject(HttpClient)
  private readonly usernameSubject = new BehaviorSubject<string | null>(null)

  /**
   * Observable of current username
   */
  username$ = this.usernameSubject.asObservable()

  /**
   * Fetch current user information from the server
   */
  fetchCurrentUser(): Observable<UserInfo> {
    return this.http.get<UserInfo>('/api/user/me').pipe(
      tap((userInfo) => {
        this.usernameSubject.next(userInfo.username)
      })
    )
  }

  /**
   * Get current username synchronously (may be null if not yet loaded)
   */
  getUsername(): string | null {
    return this.usernameSubject.value
  }
}

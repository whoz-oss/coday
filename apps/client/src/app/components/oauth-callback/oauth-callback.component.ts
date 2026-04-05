import { Component, OnInit, inject } from '@angular/core'
import { ActivatedRoute } from '@angular/router'

export const OAUTH_CALLBACK_STORAGE_KEY = 'coday_oauth_callback'

@Component({
  selector: 'app-oauth-callback',
  standalone: true,
  template: `<p>{{ message }}</p>`,
})
export class OAuthCallbackComponent implements OnInit {
  private route = inject(ActivatedRoute)

  message = 'Authentication in progress...'

  ngOnInit(): void {
    console.log('[OAuth Callback] Component initialized')

    const code = this.route.snapshot.queryParamMap.get('code')
    const state = this.route.snapshot.queryParamMap.get('state')
    const error = this.route.snapshot.queryParamMap.get('error')
    const errorDescription = this.route.snapshot.queryParamMap.get('error_description')

    console.log('[OAuth Callback] Code:', code, 'State:', state, 'Error:', error, 'Opener:', !!window.opener)

    // Handle OAuth errors (e.g., access_denied)
    if (error) {
      console.log('[OAuth Callback] OAuth error received:', error, errorDescription)

      if (!window.opener) {
        console.warn('[OAuth Callback] No window.opener — using localStorage fallback for error')
        localStorage.setItem(OAUTH_CALLBACK_STORAGE_KEY, JSON.stringify({ error, state, errorDescription }))
        this.message = 'Authentication failed. You can close this window.'
        setTimeout(() => window.close(), 1000)
        return
      }

      try {
        // Send error to parent window
        window.opener.postMessage({ error, state, errorDescription }, window.location.origin)
        console.log('[OAuth Callback] Error postMessage sent successfully')

        // Close popup
        setTimeout(() => {
          console.log('[OAuth Callback] Closing popup after error')
          window.close()
        }, 1000)
      } catch (err) {
        console.error('[OAuth Callback] Error sending error postMessage:', err)
        alert('OAuth error: Failed to communicate with parent window')
      }
      return
    }

    if (!code) {
      console.error('[OAuth Callback] Missing authorization code')
      this.message = 'OAuth callback error: Missing authorization code.'
      return
    }

    if (!window.opener) {
      console.warn('[OAuth Callback] No window.opener — using localStorage fallback')
      localStorage.setItem(OAUTH_CALLBACK_STORAGE_KEY, JSON.stringify({ code, state }))
      this.message = 'Authentication complete. You can close this window.'
      setTimeout(() => window.close(), 1000)
      return
    }

    try {
      // Send to parent window
      console.log('[OAuth Callback] Sending postMessage to parent')
      window.opener.postMessage({ code, state }, window.location.origin) // state may be null — OAuthService handles the fallback
      console.log('[OAuth Callback] postMessage sent successfully')

      // Close popup after a short delay
      setTimeout(() => {
        console.log('[OAuth Callback] Closing popup')
        window.close()
      }, 1000)
    } catch (error) {
      console.error('[OAuth Callback] Error sending postMessage:', error)
      alert('OAuth callback error: Failed to communicate with parent window')
    }
  }
}

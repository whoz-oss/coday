import { Component, OnInit, inject } from '@angular/core'
import { ActivatedRoute } from '@angular/router'
import { BrowserGlobalsService } from '../../core/services/browser-globals.service'

@Component({
  selector: 'app-oauth-callback',
  standalone: true,
  template: `<p>Authentication in progress...</p>`,
})
export class OAuthCallbackComponent implements OnInit {
  private browserGlobals = inject(BrowserGlobalsService)
  private route = inject(ActivatedRoute)

  ngOnInit(): void {
    console.log('[OAuth Callback] Component initialized')

    const code = this.route.snapshot.queryParamMap.get('code')
    const state = this.route.snapshot.queryParamMap.get('state')
    const error = this.route.snapshot.queryParamMap.get('error')
    const errorDescription = this.route.snapshot.queryParamMap.get('error_description')

    console.log(
      '[OAuth Callback] Code:',
      code,
      'State:',
      state,
      'Error:',
      error,
      'Opener:',
      !!this.browserGlobals.window.opener
    )

    // Handle OAuth errors (e.g., access_denied)
    if (error) {
      console.log('[OAuth Callback] OAuth error received:', error, errorDescription)

      if (!this.browserGlobals.window.opener) {
        alert(`OAuth error: ${error}${errorDescription ? ' - ' + errorDescription : ''}. Please close this window.`)
        return
      }

      try {
        // Send error to parent window
        this.browserGlobals.window.opener.postMessage(
          { error, state, errorDescription },
          this.browserGlobals.window.location.origin
        )
        console.log('[OAuth Callback] Error postMessage sent successfully')

        // Close popup
        setTimeout(() => {
          console.log('[OAuth Callback] Closing popup after error')
          this.browserGlobals.window.close()
        }, 1000)
      } catch (err) {
        console.error('[OAuth Callback] Error sending error postMessage:', err)
        alert('OAuth error: Failed to communicate with parent window')
      }
      return
    }

    if (!code || !state) {
      console.error('[OAuth Callback] Missing code or state')
      return
    }

    if (!this.browserGlobals.window.opener) {
      console.error('[OAuth Callback] No window.opener - popup may have been opened incorrectly')
      alert('OAuth callback error: No parent window found. Please close this window and try again.')
      return
    }

    try {
      // Send to parent window
      console.log('[OAuth Callback] Sending postMessage to parent')
      this.browserGlobals.window.opener.postMessage({ code, state }, this.browserGlobals.window.location.origin)
      console.log('[OAuth Callback] postMessage sent successfully')

      // Close popup after a short delay
      setTimeout(() => {
        console.log('[OAuth Callback] Closing popup')
        this.browserGlobals.window.close()
      }, 1000)
    } catch (error) {
      console.error('[OAuth Callback] Error sending postMessage:', error)
      alert('OAuth callback error: Failed to communicate with parent window')
    }
  }
}

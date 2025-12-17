import { Component, OnInit, inject } from '@angular/core'
import { ActivatedRoute } from '@angular/router'

@Component({
  selector: 'app-oauth-callback',
  standalone: true,
  template: `<p>Authentication in progress...</p>`,
})
export class OAuthCallbackComponent implements OnInit {
  private route = inject(ActivatedRoute)

  ngOnInit(): void {
    console.log('[OAuth Callback] Component initialized')

    const code = this.route.snapshot.queryParamMap.get('code')
    const state = this.route.snapshot.queryParamMap.get('state')
    const error = this.route.snapshot.queryParamMap.get('error')
    const errorDescription = this.route.snapshot.queryParamMap.get('error_description')

    console.log('[OAuth Callback] Code:', code, 'State:', state, 'Error:', error, 'Opener:', !!window.opener)

    // Gérer les erreurs OAuth (ex: access_denied)
    if (error) {
      console.log('[OAuth Callback] OAuth error received:', error, errorDescription)

      if (!window.opener) {
        alert(`OAuth error: ${error}${errorDescription ? ' - ' + errorDescription : ''}. Please close this window.`)
        return
      }

      try {
        // Envoyer l'erreur à la fenêtre parente
        window.opener.postMessage({ error, state, errorDescription }, window.location.origin)
        console.log('[OAuth Callback] Error postMessage sent successfully')

        // Fermer la popup
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

    if (!code || !state) {
      console.error('[OAuth Callback] Missing code or state')
      return
    }

    if (!window.opener) {
      console.error('[OAuth Callback] No window.opener - popup may have been opened incorrectly')
      alert('OAuth callback error: No parent window found. Please close this window and try again.')
      return
    }

    try {
      // Envoyer à la fenêtre parente
      console.log('[OAuth Callback] Sending postMessage to parent')
      window.opener.postMessage({ code, state }, window.location.origin)
      console.log('[OAuth Callback] postMessage sent successfully')

      // Fermer la popup après un court délai
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

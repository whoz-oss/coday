import { Component, OnInit } from '@angular/core'
import { ActivatedRoute } from '@angular/router'

@Component({
  selector: 'app-oauth-callback',
  standalone: true,
  template: `<p>Authentification en cours...</p>`,
})
export class OAuthCallbackComponent implements OnInit {
  constructor(private route: ActivatedRoute) {}

  ngOnInit(): void {
    console.log('[OAuth Callback] Component initialized')

    const code = this.route.snapshot.queryParamMap.get('code')
    const state = this.route.snapshot.queryParamMap.get('state')

    console.log('[OAuth Callback] Code:', code, 'State:', state, 'Opener:', !!window.opener)

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

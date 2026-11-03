import { inject } from '@angular/core'
import { CanActivateFn, Route, Router, UrlMatchResult, UrlSegment } from '@angular/router'
import { setupGuard, welcomeGuard } from './welcome/setup.guard'

/**
 * Temporary compatibility shim: the AgentOS UI used to live under `/agentos` and `libs/agentos-ui`
 * still navigates to absolute `/agentos/...` URLs (and old bookmarks use them). Strip that prefix and
 * redirect to the same path at the root. Remove once `libs/agentos-ui` no longer references `/agentos`.
 */
const legacyAgentosPrefixMatcher = (segments: UrlSegment[]): UrlMatchResult | null =>
  segments[0]?.path === 'agentos' ? { consumed: segments } : null

const stripLegacyAgentosPrefix: CanActivateFn = (_route, state) => {
  const rest = state.url.replace(/^\/agentos/, '')
  return inject(Router).parseUrl(rest.startsWith('/') ? rest : `/${rest}`)
}

export const appRoutes: Route[] = [
  {
    matcher: legacyAgentosPrefixMatcher,
    canActivate: [stripLegacyAgentosPrefix],
    children: [],
  },
  {
    // First-run wizard. Lazy: it is only ever loaded once per install.
    path: 'welcome',
    canActivate: [welcomeGuard],
    loadComponent: () => import('./welcome/welcome.component').then((m) => m.WelcomeComponent),
  },
  {
    // agentos-ui is lazy — a static import would bloat the eager bundle.
    // setupGuard redirects everything to /welcome until the first-run setup is done.
    path: '',
    canActivate: [setupGuard],
    loadChildren: () => import('@whoz-oss/agentos-ui').then((m) => m.AGENTOS_ROUTES),
  },
  { path: '**', redirectTo: '' },
]

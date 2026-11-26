import { routes } from './app.routes'

describe('factory cockpit routes', () => {
  it('uses /history as the canonical history route and redirects /historique', () => {
    expect(routes.find((route) => route.path === 'history')?.loadComponent).toBeDefined()
    expect(routes.find((route) => route.path === 'historique')).toMatchObject({
      pathMatch: 'full',
      redirectTo: 'history',
    })
  })

  it('routes /admin to the lazy-loaded Admin page and keeps the /reglages redirect', () => {
    expect(routes.find((route) => route.path === 'admin')?.loadComponent).toBeDefined()
    expect(routes.find((route) => route.path === 'reglages')).toMatchObject({ redirectTo: 'workflows' })
    // The admin route must be declared before the catch-all.
    expect(routes.findIndex((route) => route.path === 'admin')).toBeLessThan(
      routes.findIndex((route) => route.path === '**')
    )
  })
})

import { routes } from './app.routes'

describe('factory cockpit routes', () => {
  it('uses /history as the canonical history route and redirects /historique', () => {
    expect(routes.find((route) => route.path === 'history')?.loadComponent).toBeDefined()
    expect(routes.find((route) => route.path === 'historique')).toMatchObject({
      pathMatch: 'full',
      redirectTo: 'history',
    })
  })
})

/**
 * Default destination scope for the "Create" button on a 3-section all-scopes page.
 *
 * Admins land on `namespace` (their typical case for managing NS-shared configs); non-admins
 * land on `userOnNs` (what they can actually create). Without this gate, a non-admin clicking
 * Create would submit a `namespace` form and get a silent 403. The form's radio still lets
 * the user re-target before submit.
 */
export function defaultCreateScope(isAdmin: boolean): 'namespace' | 'userOnNs' {
  return isAdmin ? 'namespace' : 'userOnNs'
}

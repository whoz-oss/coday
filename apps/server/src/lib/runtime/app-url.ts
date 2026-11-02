/**
 * The URL a human should open: shared by the browser auto-open and by the `status` pane, so they can never disagree.
 *
 * It is always `/welcome`, whatever the profile and the setup state: the first-run wizard while the setup is pending,
 * otherwise a plain loading page (download, Java, AgentOS, Factory) that hands over to the app once AgentOS is up.
 * That page only talks to `/api/setup/*`, which Express answers without AgentOS, so it can open as soon as Express
 * listens. Undefined until provisioning chose the Express port.
 */

import { RuntimeState } from './state'

export function appUrl(state: Pick<RuntimeState, 'ports'> | undefined): string | undefined {
  const port = state?.ports.express
  return port ? `http://localhost:${port}/welcome` : undefined
}

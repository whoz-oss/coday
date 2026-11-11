/**
 * Dev proxy of the Angular dev server (apps/client `serve`), used when the dev server is opened directly instead of
 * through Express (the normal way, see apps/web/README.md, Ports). Every port is derived from the port scheme
 * (apps/server/src/lib/runtime/port-scheme.json, the single source of truth): the dev block, or the one of
 * CODAY_PORT_BASE (scripts/web-dev-tmux.sh sets it for each stack). Express is base+offsets.server, AgentOS
 * base+offsets.agentos.
 */
import { readFileSync } from 'node:fs'

const scheme = JSON.parse(
  readFileSync(new URL('../server/src/lib/runtime/port-scheme.json', import.meta.url), 'utf8')
)

const fromEnv = Number(process.env.CODAY_PORT_BASE)
const base = Number.isInteger(fromEnv) && fromEnv >= 1024 ? fromEnv : scheme.profiles.dev.base
const target = (role) => `http://localhost:${base + scheme.offsets[role]}`

export default {
  '/api/agentos': {
    changeOrigin: true,
    logLevel: 'debug',
    pathRewrite: { '^/api/agentos': '' },
    secure: false,
    target: target('agentos'),
  },
  '/api/setup': {
    changeOrigin: true,
    logLevel: 'debug',
    secure: false,
    target: target('server'),
  },
}

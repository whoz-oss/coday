import * as fs from 'fs'
import * as path from 'path'
import { execFileSync } from 'child_process'
import { candidateBases, PORT_BASES, PORT_BLOCK_SIZE, portsFor } from './port-scheme'

/**
 * Guards the files that CANNOT compute their ports (Nx and Angular project.json, IDE run configurations): each repeats
 * a DEV value of the port scheme. They must equal portsFor(dev base) of port-scheme.json, the single source of truth;
 * a drift fails here with the file and the value expected. Files that CAN compute (server runtime, web-local.ts,
 * playwright.config.ts, proxy.conf.mjs, web-dev-tmux.sh) must not hold a port literal at all.
 */

const root = path.resolve(__dirname, '../../../../..')
const dev = portsFor(PORT_BASES.dev)

const read = (file: string): string => fs.readFileSync(path.join(root, file), 'utf8')
const readJson = (file: string): Record<string, any> => JSON.parse(read(file))

/** Fails with the file, what is checked, the value found and the value expected. */
function expectPort(file: string, what: string, actual: unknown, expected: string | number): void {
  if (String(actual) !== String(expected)) {
    throw new Error(
      `${file}: ${what} is ${JSON.stringify(actual)}, expected ${JSON.stringify(String(expected))} ` +
        `(DEV block of apps/server/src/lib/runtime/port-scheme.json, base ${PORT_BASES.dev}). Update the file, or the JSON and every file listed in this spec.`
    )
  }
}

const url = (port: number): string => `http://localhost:${port}`

/** `<env name="X" value="Y" />` of an IntelliJ run configuration. */
function ideEnv(file: string): Record<string, string> {
  const env: Record<string, string> = {}
  for (const match of read(file).matchAll(/<env name="([^"]+)" value="([^"]*)"/g))
    env[match[1] as string] = match[2] as string
  return env
}

describe('port scheme consistency (files that cannot compute their ports)', () => {
  it('apps/client/project.json: the serve port is the dev client port', () => {
    const file = 'apps/client/project.json'
    expectPort(file, 'targets.serve.options.port', readJson(file).targets.serve.options.port, dev.client)
  })

  it('apps/factory-cockpit/project.json: the serve port is the dev cockpit port', () => {
    const file = 'apps/factory-cockpit/project.json'
    expectPort(file, 'targets.serve.options.port', readJson(file).targets.serve.options.port, dev.factoryCockpit)
  })

  describe('apps/web/project.json (dev targets)', () => {
    const file = 'apps/web/project.json'
    const targets = () => readJson(file).targets

    it('dev-server: points at the dev AgentOS and Factory, and leaves PORT out (BUILD_ENV=development is the dev base)', () => {
      const env = targets()['dev-server'].options.env
      expectPort(file, 'dev-server env.AGENTOS_URL', env.AGENTOS_URL, url(dev.agentos))
      expectPort(file, 'dev-server env.FACTORY_URL', env.FACTORY_URL, url(dev.factory))
      expectPort(file, 'dev-server env.BUILD_ENV', env.BUILD_ENV, 'development')
      expectPort(file, 'dev-server env.PORT (must be absent)', env.PORT, undefined as unknown as string)
    })

    it('dev-agentos: AgentOS and Bolt ports', () => {
      const env = targets()['dev-agentos'].options.env
      expectPort(file, 'dev-agentos env.SERVER_PORT', env.SERVER_PORT, dev.agentos)
      expectPort(
        file,
        'dev-agentos env.AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT',
        env.AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT,
        dev.bolt
      )
      expectPort(file, 'dev-agentos env.SPRING_NEO4J_URI', env.SPRING_NEO4J_URI, `bolt://localhost:${dev.bolt}`)
    })

    it('dev-factory: Factory port and the AgentOS it talks to', () => {
      const env = targets()['dev-factory'].options.env
      expectPort(file, 'dev-factory env.SERVER_PORT', env.SERVER_PORT, dev.factory)
      expectPort(file, 'dev-factory env.AGENTOS_URL', env.AGENTOS_URL, url(dev.agentos))
    })
  })

  describe('.idea/runConfigurations', () => {
    it('AgentOSApplication.xml: AgentOS and Bolt ports', () => {
      const file = '.idea/runConfigurations/AgentOSApplication.xml'
      const env = ideEnv(file)
      expectPort(file, 'SERVER_PORT', env['SERVER_PORT'], dev.agentos)
      expectPort(
        file,
        'AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT',
        env['AGENTOS_PERSISTENCE_EMBEDDED_BOLT_PORT'],
        dev.bolt
      )
      expectPort(file, 'SPRING_NEO4J_URI', env['SPRING_NEO4J_URI'], `bolt://localhost:${dev.bolt}`)
    })

    it('FactoryServiceApplication.xml: Factory port and its AgentOS', () => {
      const file = '.idea/runConfigurations/FactoryServiceApplication.xml'
      const env = ideEnv(file)
      expectPort(file, 'SERVER_PORT', env['SERVER_PORT'], dev.factory)
      expectPort(file, 'AGENTOS_URL', env['AGENTOS_URL'], url(dev.agentos))
    })

    it('coday.xml: the dev AgentOS and Factory it points at, and no fixed PORT (the packaged scheme picks one)', () => {
      const file = '.idea/runConfigurations/coday.xml'
      const env = ideEnv(file)
      expectPort(file, 'AGENTOS_URL', env['AGENTOS_URL'], url(dev.agentos))
      expectPort(file, 'FACTORY_URL', env['FACTORY_URL'], url(dev.factory))
      expectPort(file, 'PORT (must be absent)', env['PORT'], undefined as unknown as string)
    })

    it('client_debug.xml: the browser opens the dev client', () => {
      const file = '.idea/runConfigurations/client_debug.xml'
      const match = read(file).match(/url="([^"]+)"/)
      expectPort(file, 'browser url', match?.[1], url(dev.client))
    })
  })
})

describe('port scheme consistency (files that compute: they really derive from the JSON)', () => {
  it('apps/client/proxy.conf.mjs targets Express and AgentOS of the dev block, or of CODAY_PORT_BASE', () => {
    const run = (env: NodeJS.ProcessEnv): Record<string, { target: string }> =>
      JSON.parse(
        execFileSync(
          process.execPath,
          [
            '--input-type=module',
            '-e',
            'const m = await import(process.argv[1]); console.log(JSON.stringify(m.default))',
            path.join(root, 'apps/client/proxy.conf.mjs'),
          ],
          { env: { PATH: process.env['PATH'], ...env }, encoding: 'utf8' }
        )
      )
    const file = 'apps/client/proxy.conf.mjs'
    const byDefault = run({})
    expectPort(file, "default '/api/agentos' target", byDefault['/api/agentos']?.target, url(dev.agentos))
    expectPort(file, "default '/api/setup' target", byDefault['/api/setup']?.target, url(dev.server))
    const second = portsFor(candidateBases('dev')[1] as number)
    const moved = run({ CODAY_PORT_BASE: String(candidateBases('dev')[1]) })
    expectPort(file, "CODAY_PORT_BASE '/api/agentos' target", moved['/api/agentos']?.target, url(second.agentos))
    expectPort(file, "CODAY_PORT_BASE '/api/setup' target", moved['/api/setup']?.target, url(second.server))
  })

  it('scripts/web-dev-tmux.sh reads the same numbers as port-scheme.ts (its node reader, run in bash)', () => {
    const script = read('scripts/web-dev-tmux.sh')
    const start = script.indexOf('eval "$(node -e')
    const end = script.indexOf('"${PORT_SCHEME_FILE}")"', start)
    if (start < 0 || end < 0)
      throw new Error('scripts/web-dev-tmux.sh: the `eval "$(node -e ...)"` port reader was not found')
    const reader = script.slice(start, end + '"${PORT_SCHEME_FILE}")"'.length)
    const out = execFileSync(
      'bash',
      [
        '-c',
        `${reader}\necho "$DEV_PORT_BASE $DEV_BLOCK_STEP $PORT_BLOCK_SIZE $MAX_BLOCKS $OFFSET_SERVER $OFFSET_CLIENT $OFFSET_COCKPIT $OFFSET_AGENTOS $OFFSET_FACTORY $OFFSET_BOLT"`,
      ],
      {
        env: {
          PATH: process.env['PATH'],
          PORT_SCHEME_FILE: path.join(root, 'apps/server/src/lib/runtime/port-scheme.json'),
        },
        encoding: 'utf8',
      }
    )
    const [base, step, size, count, ...offsets] = out.trim().split(' ').map(Number)
    const blocks = candidateBases('dev')
    const file = 'scripts/web-dev-tmux.sh'
    expectPort(file, 'dev base read from the JSON', base, PORT_BASES.dev)
    expectPort(file, 'dev block step read from the JSON', step, (blocks[1] as number) - (blocks[0] as number))
    expectPort(file, 'block size read from the JSON', size, PORT_BLOCK_SIZE)
    expectPort(file, 'number of candidate blocks (MAX_BLOCKS)', count, blocks.length)
    const block = portsFor(0)
    expectPort(
      file,
      'role offsets (server, client, cockpit, agentos, factory, bolt)',
      offsets.join(','),
      [block.server, block.client, block.factoryCockpit, block.agentos, block.factory, block.bolt].join(',')
    )
  })
})

describe('port scheme consistency (files that compute: no port literal)', () => {
  /** The file without its comment lines (`#`, `//`, ` *`): documentation may show the numbers, the code may not. */
  const code = (file: string): string =>
    read(file)
      .split('\n')
      .filter((line) => !/^\s*(#|\/\/|\*|\/\*)/.test(line))
      .join('\n')

  it.each([
    'apps/client/proxy.conf.mjs',
    'apps/client-e2e/playwright.config.ts',
    'scripts/web-dev-tmux.sh',
    'scripts/web-local.ts',
  ])('%s derives every port from port-scheme.json', (file) => {
    const literals = code(file).match(/\b[345]\d{3}\b/g) ?? []
    if (literals.length > 0) {
      throw new Error(
        `${file}: port-like literal(s) ${literals.join(', ')} in the code. Derive them from apps/server/src/lib/runtime/port-scheme.json ` +
          `(port-scheme.ts, or the node -e reader of web-dev-tmux.sh).`
      )
    }
  })

  it('the guard itself sees a drift (self-test of the message)', () => {
    expect(() => expectPort('some/file.json', 'port', 4999, dev.client)).toThrow(
      /some\/file\.json: port is 4999, expected "4001"/
    )
  })
})

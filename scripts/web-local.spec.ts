import * as path from 'path'
import {
  AGENTOS_PLUGINS,
  builtJarGlob,
  devJarTarget,
  FACTORY_PLUGINS,
  installManifest,
  isSetupDone,
  localLayout,
  NPM_PACKAGES,
  resolveLocalRoot,
  tarballPrefix,
  unsafeRootReason,
} from './utils/web-local-helpers'

describe('web-local helpers', () => {
  it('packs the four npm packages of the published runtime', () => {
    expect(NPM_PACKAGES.map((p) => p.name)).toEqual([
      '@whoz-oss/coday-client',
      '@whoz-oss/coday-factory-cockpit',
      '@whoz-oss/coday-server',
      '@whoz-oss/coday-web',
    ])
  })

  it('derives the tarball prefix pnpm pack uses', () => {
    expect(tarballPrefix('@whoz-oss/coday-client')).toBe('whoz-oss-coday-client-')
    expect(tarballPrefix('@whoz-oss/coday-web')).toBe('whoz-oss-coday-web-')
  })

  it('installs the tarballs directly and forces inner dependencies onto them (no registry lookup)', () => {
    const manifest = installManifest({
      '@whoz-oss/coday-web': '/p/web.tgz',
      '@whoz-oss/coday-server': '/p/server.tgz',
    }) as {
      dependencies: Record<string, string>
      overrides: Record<string, string>
    }
    expect(manifest.dependencies['@whoz-oss/coday-web']).toBe('file:/p/web.tgz')
    expect(manifest.overrides).toEqual(manifest.dependencies)
  })

  it('looks for JARs in the Gradle build/libs of the project', () => {
    expect(builtJarGlob('agentos', 'agentos-service')).toEqual({
      dir: path.join('agentos', 'agentos-service', 'build', 'libs'),
      startsWith: 'agentos-service',
    })
    expect(builtJarGlob('factory', 'factory-forge-plugin').dir).toBe(
      path.join('factory', 'factory-forge-plugin', 'build', 'libs')
    )
  })

  it('places JARs in the `dev` sentinel layout jar-set.ts expects: service at the root, plugins in plugins/', () => {
    expect(devJarTarget('/c', 'agentos', 'agentos-service')).toBe(path.join('/c', 'agentos', 'agentos-service-dev.jar'))
    expect(devJarTarget('/c', 'agentos', 'agentos-bash-plugin')).toBe(
      path.join('/c', 'agentos', 'plugins', 'agentos-bash-plugin-dev.jar')
    )
    expect(devJarTarget('/c', 'factory', 'factory-service')).toBe(path.join('/c', 'factory', 'factory-service-dev.jar'))
  })

  it('never places a JAR outside the jar directories, so the data dirs of a kept config are left alone', () => {
    for (const kind of ['agentos', 'factory'] as const) {
      for (const artifactId of [`${kind}-service`, ...(kind === 'agentos' ? AGENTOS_PLUGINS : FACTORY_PLUGINS)]) {
        const target = devJarTarget('/c', kind, artifactId)
        expect(path.relative(path.join('/c', kind, 'data'), target).startsWith('..')).toBe(true)
        expect(target.endsWith('-dev.jar')).toBe(true)
      }
    }
  })

  it('ships every plugin the release publishes', () => {
    expect(AGENTOS_PLUGINS).toEqual([
      'agentos-bash-plugin',
      'agentos-file-plugin',
      'agentos-mcp-plugin',
      'agentos-http-plugin',
      'agentos-tmux-plugin',
      'agentos-git-plugin',
    ])
    expect(FACTORY_PLUGINS).toEqual(['factory-forge-plugin'])
  })
})

describe('local root', () => {
  const home = '/home/me'

  it('defaults to the persistent ~/.coday-local', () => {
    expect(resolveLocalRoot({}, home)).toEqual({ root: path.join(home, '.coday-local'), source: 'default' })
  })

  it('honours CODAY_LOCAL_DIR, which wins over the deprecated CODAY_FIRST_RUN_DIR', () => {
    expect(resolveLocalRoot({ CODAY_LOCAL_DIR: '/x/local', CODAY_FIRST_RUN_DIR: '/x/old' }, home)).toEqual({
      root: '/x/local',
      source: 'CODAY_LOCAL_DIR',
    })
  })

  it('still honours the deprecated CODAY_FIRST_RUN_DIR and says so', () => {
    const resolved = resolveLocalRoot({ CODAY_FIRST_RUN_DIR: '/x/old' }, home)
    expect(resolved.root).toBe('/x/old')
    expect(resolved.source).toMatch(/deprecated/)
  })

  it('keeps the layout of the previous script under the root', () => {
    expect(localLayout('/r')).toEqual({
      packs: path.join('/r', 'packs'),
      install: path.join('/r', 'install'),
      config: path.join('/r', 'config'),
      files: path.join('/r', 'files'),
      filesFolder: path.join('/r', 'files', 'Coday'),
    })
  })

  it('accepts roots that are away from the real config', () => {
    expect(unsafeRootReason('/home/me/.coday-local', home)).toBeUndefined()
    expect(unsafeRootReason('/tmp/coday-first-run-e2e-1', home, ['/home/me/dev/coday'])).toBeUndefined()
  })

  it.each([
    ['the filesystem root', '/'],
    ['the home directory', '/home/me'],
    ['a parent of the home directory', '/home'],
    ['~/.coday', '/home/me/.coday'],
    ['inside ~/.coday', '/home/me/.coday/local'],
    ['~/Coday', '/home/me/Coday'],
    ['inside ~/Coday', '/home/me/Coday/x'],
  ])('refuses %s', (_label, root) => {
    expect(unsafeRootReason(root, home)).toBeDefined()
  })

  it('refuses a root that is, contains or lies inside a protected extra path (the repository)', () => {
    expect(unsafeRootReason('/home/me/dev/coday', home, ['/home/me/dev/coday'])).toBeDefined()
    expect(unsafeRootReason('/home/me/dev', home, ['/home/me/dev/coday'])).toBeDefined()
    expect(unsafeRootReason('/home/me/dev/coday/tmp', home, ['/home/me/dev/coday'])).toBeDefined()
  })

  it('knows when the wizard was completed', () => {
    expect(isSetupDone({ identity: { email: 'a@b.c' }, setupDone: true })).toBe(true)
    expect(isSetupDone({ identity: { email: 'a@b.c' } })).toBe(false)
    expect(isSetupDone({ setupDone: 'yes' })).toBe(false)
    expect(isSetupDone(undefined)).toBe(false)
    expect(isSetupDone(null)).toBe(false)
  })
})

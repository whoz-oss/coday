import * as path from 'path'
import {
  AGENTOS_PLUGINS,
  builtJarGlob,
  devJarTarget,
  FACTORY_PLUGINS,
  installManifest,
  NPM_PACKAGES,
  tarballPrefix,
} from './utils/first-run-helpers'

describe('first-run sandbox helpers', () => {
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

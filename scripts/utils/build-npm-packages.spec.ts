import { buildNpmPackages, NPM_BUILD_COMMAND } from './build-npm-packages'

describe('NPM_BUILD_COMMAND', () => {
  it('builds every platform:npm project through the build target', () => {
    expect(NPM_BUILD_COMMAND).toContain('run-many')
    expect(NPM_BUILD_COMMAND).toMatch(/-t build(\s|$)/)
    expect(NPM_BUILD_COMMAND).toContain('--projects=tag:platform:npm')
  })

  it('excludes the desktop bundle, whose publish is a no-op', () => {
    expect(NPM_BUILD_COMMAND).toContain('--exclude=tag:desktop-bundle')
  })
})

describe('buildNpmPackages', () => {
  beforeEach(() => {
    jest.spyOn(console, 'log').mockImplementation(() => undefined)
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  it('runs the build command exactly once', () => {
    const exec = jest.fn()

    buildNpmPackages(exec)

    expect(exec).toHaveBeenCalledTimes(1)
    expect(exec).toHaveBeenCalledWith(NPM_BUILD_COMMAND)
  })

  it('rethrows when a build fails, so the caller never reaches the publish', () => {
    // Regression for #1781: a failing build must stop the flow before any package is published.
    const failure = new Error('Command failed: nx run-many -t build')
    const exec = jest.fn(() => {
      throw failure
    })
    const publish = jest.fn()

    const release = () => {
      buildNpmPackages(exec)
      publish()
    }

    expect(release).toThrow(failure)
    expect(publish).not.toHaveBeenCalled()
  })
})

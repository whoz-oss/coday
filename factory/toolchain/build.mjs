import { mkdir, rename, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { build } from 'esbuild'

const toolchainDirectory = dirname(fileURLToPath(import.meta.url))
const factoryDirectory = resolve(toolchainDirectory, '..')
const runtimeDirectory = resolve(factoryDirectory, 'runtime')
const diagnosticsDirectory = resolve(factoryDirectory, 'dist/factory-operational')
const runtimeFile = resolve(runtimeDirectory, 'factory-operational.mjs')
const temporaryRuntimeFile = `${runtimeFile}.tmp`
const sourcemapPath = resolve(diagnosticsDirectory, 'factory-operational.mjs.map')
const metafilePath = resolve(diagnosticsDirectory, 'factory-operational.meta.json')

await Promise.all([mkdir(runtimeDirectory, { recursive: true }), mkdir(diagnosticsDirectory, { recursive: true })])
try {
  const result = await build({
    entryPoints: [resolve(factoryDirectory, 'src/entrypoints/factory-operational.ts')],
    outfile: temporaryRuntimeFile,
    bundle: true,
    format: 'esm',
    platform: 'node',
    target: ['node22.12'],
    packages: 'bundle',
    external: ['node:*'],
    splitting: false,
    sourcemap: 'external',
    sourcesContent: false,
    metafile: true,
    legalComments: 'none',
    banner: { js: '// GENERATED FILE — DO NOT EDIT. Source: factory/src/entrypoints/factory-operational.ts' },
  })
  await rename(`${temporaryRuntimeFile}.map`, sourcemapPath)
  await writeFile(metafilePath, `${JSON.stringify(result.metafile, null, 2)}\n`, 'utf8')
  await rename(temporaryRuntimeFile, runtimeFile)
} catch (error) {
  await Promise.all([rm(temporaryRuntimeFile, { force: true }), rm(`${temporaryRuntimeFile}.map`, { force: true })])
  throw error
}

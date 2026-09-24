import { mkdir, rename, rm, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { build } from 'esbuild'

const toolchainDirectory = dirname(fileURLToPath(import.meta.url))
const factoryDirectory = resolve(toolchainDirectory, '..')
const runtimeDirectory = resolve(factoryDirectory, 'runtime')
const diagnosticsDirectory = resolve(factoryDirectory, 'dist/active-case-contract')
const runtimeFile = resolve(runtimeDirectory, 'active-case-contract.mjs')
const temporaryRuntimeFile = `${runtimeFile}.tmp`
const sourcemapPath = resolve(diagnosticsDirectory, 'active-case-contract.mjs.map')
const metafilePath = resolve(diagnosticsDirectory, 'active-case-contract.meta.json')

await Promise.all([
  mkdir(runtimeDirectory, { recursive: true }),
  mkdir(diagnosticsDirectory, { recursive: true }),
])

try {
  const result = await build({
    entryPoints: [resolve(factoryDirectory, 'src/entrypoints/active-case-contract.ts')],
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
    banner: { js: '// GENERATED FILE — DO NOT EDIT. Source: factory/src/entrypoints/active-case-contract.ts' },
  })

  await rename(`${temporaryRuntimeFile}.map`, sourcemapPath)
  await writeFile(metafilePath, `${JSON.stringify(result.metafile, null, 2)}\n`, 'utf8')
  await rename(temporaryRuntimeFile, runtimeFile)
} catch (error) {
  await Promise.all([
    rm(temporaryRuntimeFile, { force: true }),
    rm(`${temporaryRuntimeFile}.map`, { force: true }),
  ])
  throw error
}

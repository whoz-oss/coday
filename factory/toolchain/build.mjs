import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { build } from 'esbuild'

const toolchainDirectory = dirname(fileURLToPath(import.meta.url))
const factoryDirectory = resolve(toolchainDirectory, '..')
const outputDirectory = resolve(factoryDirectory, 'dist/stage-1')
const outputFile = resolve(outputDirectory, 'active-case.mjs')
const metafilePath = resolve(outputDirectory, 'active-case.meta.json')

await mkdir(outputDirectory, { recursive: true })

const result = await build({
  entryPoints: [resolve(factoryDirectory, 'src/lib/active-case.ts')],
  outfile: outputFile,
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
})

await writeFile(metafilePath, `${JSON.stringify(result.metafile, null, 2)}\n`, 'utf8')

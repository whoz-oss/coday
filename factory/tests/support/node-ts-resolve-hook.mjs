// Node ESM resolve hook for the SQL adapter conformance suite.
//
// The Factory TypeScript sources use the `NodeNext` convention of importing
// sibling modules with a `.js` specifier (`./db.js` for `./db.ts`). esbuild and
// `tsc` resolve those, but Node's native `--experimental-strip-types` resolver
// does not. This hook rewrites a relative `./x.js` / `../x.js` specifier to the
// sibling `x.ts` when (and only when) the `.js` file is genuinely missing, so the
// conformance script can import the adapters directly with plain `node`, without
// a bundling step and without touching any source import.

import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

export async function resolve(specifier, context, nextResolve) {
  if (!specifier.endsWith('.js') || !(specifier.startsWith('./') || specifier.startsWith('../'))) {
    return nextResolve(specifier, context)
  }
  try {
    return await nextResolve(specifier, context)
  } catch (error) {
    if (error?.code !== 'ERR_MODULE_NOT_FOUND') throw error
    const parent = context.parentURL ? new URL('.', context.parentURL) : undefined
    if (!parent) throw error
    const candidate = new URL(`${specifier.slice(0, -3)}.ts`, parent)
    if (!existsSync(fileURLToPath(candidate))) throw error
    return { url: candidate.href, shortCircuit: true }
  }
}

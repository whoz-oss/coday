// @ts-nocheck -- barrel re-exporting source modules; the explicit `.ts`
// extensions below are required by Node's native TypeScript type stripping at
// runtime, which the factory tsconfig (predating `allowImportingTsExtensions`)
// does not enable. Re-exported declarations keep their original types.
//
// Identity domain barrel: re-exports the TrustContext vocabulary, the local
// Fake IdP and the server-side membership resolvers used by the Factory HTTP
// boundary.

export * from './trust-context.ts'
export * from './fake-idp.ts'
export * from './membership-resolver.ts'

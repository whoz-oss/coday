# Coday identity bridge

## What changed

The Factory now has a dedicated Coday identity bridge for B6-T2a. It accepts an email resolved by the trusted proxy/authentication layer and mints a compact HS256 JWT through the existing Fake IdP, using the Factory’s shared secret. The token uses the email as `principalId` (and `sub`), carries `principalType` (`human` by default, or `service`) and delegated `scopes` (empty by default), and can optionally override its audience and lifetime.

The bridge is fail-closed: missing, blank, or malformed emails and invalid principal types, scopes, audience, expiry, or explicitly supplied secrets raise `CodayIdentityError`. `tryMintCodayIdentityToken` and the class’s `tryMintToken` provide a non-throwing `string | null` form. Email input is trimmed but not case-folded, and the module does not read request headers or other unsigned client identity data.

`factory/src/domain/identity/index.ts` re-exports the bridge. The source comments document that this Fake IdP HMAC implementation is an integration/development seam; a future OIDC/JWKS provider should replace shared-secret minting and HMAC verification with provider-issued tokens and asymmetric JWKS validation while retaining the TrustContext vocabulary.

## Files

- `factory/src/domain/identity/coday-identity-bridge.ts` — validation, `mintCodayIdentityToken`, alias `issueCodayIdentityToken`, non-throwing helper, normalized identity type, error type, constants, and `CodayIdentityBridge` wrapper.
- `factory/src/domain/identity/index.ts` — barrel export for the new module.
- `factory/tests/test-coday-identity-bridge.mjs` — offline integration and validation coverage.
- `specs/3e6aac56_identity_bridge_coday.md` — implementation scope, decisions, proposed API, and verification plan.

## How to verify/use

Mint a token with an explicit shared secret:

```js
const token = mintCodayIdentityToken(
  { email: 'user@whoz.com', scopes: ['read', 'write'] },
  factorySecret,
)
```

Pass it as `Authorization: Bearer <token>` to the existing `extractTrustContext` boundary. A valid token produces `authenticationMethod: 'jwt'`, `principalId: 'user@whoz.com'`, the selected principal type, and the supplied scopes. Wrong-secret, expired, tampered, unsigned, or malformed tokens are rejected and do not create an authenticated JWT context.

Run the standalone offline test:

```bash
node factory/tests/test-coday-identity-bridge.mjs
```

The test covers valid bridge-to-`TrustContext` flow, defaults and custom claims, invalid-token fallback, fail-closed input validation, class/free-function APIs, and protection against forged unsigned headers. No changes were made to the existing trust-context boundary, membership resolver, admin authorization seam, or operational bundle.

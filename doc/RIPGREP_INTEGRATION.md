# Ripgrep Binary Integration

## Overview

Coday incorporates the ripgrep binary via the `@vscode/ripgrep` npm package to ensure portability across different platforms without requiring users to install ripgrep separately.

## Implementation

### Package Dependency

The `@vscode/ripgrep` package is included in `package.json`:

```json
"dependencies": {
  "@vscode/ripgrep": "1.15.9",
  ...
}
```

This package provides pre-compiled ripgrep binaries for all major platforms (Windows, macOS, Linux) with different architectures.

### Usage

The ripgrep binary is used in `libs/function/generate-file-tree.ts` to scan project files:

```typescript
import { rgPath } from '@vscode/ripgrep'
import { execSync } from 'child_process'

// Use the binary path provided by the package
const output = execSync(`"${rgPath}" --files`, {
  cwd: rootPath,
  encoding: 'utf-8',
  timeout,
  maxBuffer
})
```

### Benefits

1. **Portability**: No need for users to install ripgrep separately
2. **Consistency**: Same ripgrep version across all platforms
3. **Simplicity**: Works out-of-the-box after `pnpm install`
4. **Maintenance**: Ripgrep updates handled via npm package updates

## Similar Integration

This follows the same pattern as the Sharp library integration for image processing, where the native binary is bundled with the npm package for portability.

## Related Issues

- GitHub Issue #306: Incorporate ripgrep binary

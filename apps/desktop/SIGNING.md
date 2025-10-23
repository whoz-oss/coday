# Code Signing Guide for Coday Desktop

## macOS Code Signing

### Prerequisites

1. **Apple Developer Account** - Required for code signing certificates
2. **Developer ID Application Certificate** - Download from Apple Developer portal
3. **Install the certificate** - Double-click to install in Keychain Access

### Setup

1. **Get your certificate identity:**
```bash
security find-identity -v -p codesigning
```

This will show something like:
```
1) XXXXXXXXXX "Developer ID Application: Your Name (TEAM_ID)"
```

2. **Set environment variables:**

Create a `.env` file in `apps/desktop/` or set them in your shell:

```bash
# For Developer ID (distribution outside App Store)
export CSC_NAME="Developer ID Application: Your Name (TEAM_ID)"

# Or use the identity hash
export CSC_LINK="/path/to/certificate.p12"
export CSC_KEY_PASSWORD="your_certificate_password"

# For App Store distribution
export CSC_NAME="3rd Party Mac Developer Application: Your Name (TEAM_ID)"
```

### Build and Sign

```bash
# Build with automatic signing (if certificate is in Keychain)
pnpm nx run desktop:package-dist

# Or with explicit certificate
CSC_NAME="Developer ID Application: Your Name" pnpm nx run desktop:package-dist
```

### Notarization (Required for macOS 10.15+)

For apps distributed outside the App Store, you need to notarize:

1. **Set Apple ID credentials:**
```bash
export APPLE_ID="your@email.com"
export APPLE_ID_PASSWORD="app-specific-password"  # Generate in Apple ID settings
export APPLE_TEAM_ID="YOUR_TEAM_ID"
```

2. **Build and notarize:**
```bash
# electron-builder will automatically notarize if credentials are set
pnpm nx run desktop:package-dist
```

3. **Manual notarization (if needed):**
```bash
# Submit for notarization
xcrun notarytool submit apps/desktop/release/Coday-0.32.0.dmg \
  --apple-id "your@email.com" \
  --password "app-specific-password" \
  --team-id "YOUR_TEAM_ID" \
  --wait

# Staple the notarization ticket
xcrun stapler staple apps/desktop/release/Coday-0.32.0.dmg
```

### Verify Signing

```bash
# Check signature
codesign -dv --verbose=4 apps/desktop/release/mac/Coday.app

# Check if notarized
spctl -a -vv -t install apps/desktop/release/mac/Coday.app

# Check DMG
codesign -dv apps/desktop/release/Coday-0.32.0.dmg
```

---

## Windows Code Signing

### Prerequisites

1. **Code Signing Certificate** - Purchase from a Certificate Authority (DigiCert, Sectigo, etc.)
2. **Certificate file** - Usually `.p12` or `.pfx` format

### Setup

Set environment variables:

```bash
# Windows certificate
export CSC_LINK="/path/to/certificate.pfx"
export CSC_KEY_PASSWORD="certificate_password"

# Or for Azure Key Vault
export AZURE_KEY_VAULT_URI="https://your-vault.vault.azure.net/"
export AZURE_KEY_VAULT_CERTIFICATE="cert-name"
export AZURE_CLIENT_ID="client-id"
export AZURE_CLIENT_SECRET="client-secret"
export AZURE_TENANT_ID="tenant-id"
```

### Build and Sign

```bash
pnpm nx run desktop:package-dist
```

### Verify Signing

```powershell
# On Windows
signtool verify /pa /v "apps\desktop\release\Coday Setup 0.32.0.exe"
```

---

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Build and Sign Desktop App

on:
  push:
    tags:
      - 'v*'

jobs:
  build-mac:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '20'
      
      - name: Install dependencies
        run: pnpm install
      
      - name: Import Certificate
        env:
          CERTIFICATE_BASE64: ${{ secrets.MACOS_CERTIFICATE }}
          CERTIFICATE_PASSWORD: ${{ secrets.MACOS_CERT_PASSWORD }}
        run: |
          echo $CERTIFICATE_BASE64 | base64 --decode > certificate.p12
          security create-keychain -p actions build.keychain
          security default-keychain -s build.keychain
          security unlock-keychain -p actions build.keychain
          security import certificate.p12 -k build.keychain -P $CERTIFICATE_PASSWORD -T /usr/bin/codesign
          security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k actions build.keychain
      
      - name: Build and Sign
        env:
          CSC_NAME: ${{ secrets.CSC_NAME }}
          APPLE_ID: ${{ secrets.APPLE_ID }}
          APPLE_ID_PASSWORD: ${{ secrets.APPLE_ID_PASSWORD }}
          APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
        run: pnpm nx run desktop:package-dist
      
      - name: Upload artifacts
        uses: actions/upload-artifact@v3
        with:
          name: macos-dmg
          path: apps/desktop/release/*.dmg

  build-windows:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '20'
      
      - name: Install dependencies
        run: pnpm install
      
      - name: Build and Sign
        env:
          CSC_LINK: ${{ secrets.WIN_CSC_LINK }}
          CSC_KEY_PASSWORD: ${{ secrets.WIN_CSC_KEY_PASSWORD }}
        run: pnpm nx run desktop:package-dist
      
      - name: Upload artifacts
        uses: actions/upload-artifact@v3
        with:
          name: windows-installer
          path: apps/desktop/release/*.exe
```

---

## Troubleshooting

### macOS: "App is damaged and can't be opened"
- App is not signed or notarized
- Run: `xattr -cr /path/to/Coday.app` to remove quarantine flag (for testing only)

### macOS: "Developer cannot be verified"
- App is signed but not notarized
- Need to notarize the app

### Windows: "Windows protected your PC"
- App is not signed
- Users need to click "More info" → "Run anyway"
- Proper signing eliminates this warning

### Certificate not found
```bash
# List available certificates
security find-identity -v -p codesigning

# Make sure the certificate is in your default keychain
```

---

## Skip Signing (Development Only)

To build without signing (for local testing):

```bash
# Skip signing
CSC_IDENTITY_AUTO_DISCOVERY=false pnpm nx run desktop:package-dist

# Or set in environment
export CSC_IDENTITY_AUTO_DISCOVERY=false
```

**Note:** Unsigned apps will show security warnings on both macOS and Windows.

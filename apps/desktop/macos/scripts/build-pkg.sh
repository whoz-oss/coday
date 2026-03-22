#!/bin/bash
# Build macOS .pkg installer for Coday Desktop
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
RELEASE_DIR="$APP_DIR/release"
PKG_BUILD_DIR="$RELEASE_DIR/pkg-build"

# Find the .app bundle
APP_BUNDLE=$(find "$RELEASE_DIR" -name "Coday.app" -type d | head -1)
if [ -z "$APP_BUNDLE" ]; then
    # Look in mac or mac-arm64 subdirectories
    APP_BUNDLE=$(find "$RELEASE_DIR/mac" -name "Coday.app" -type d 2>/dev/null | head -1)
    if [ -z "$APP_BUNDLE" ]; then
        APP_BUNDLE=$(find "$RELEASE_DIR/mac-arm64" -name "Coday.app" -type d 2>/dev/null | head -1)
    fi
fi

if [ -z "$APP_BUNDLE" ]; then
    echo "Error: Coday.app not found in $RELEASE_DIR"
    exit 1
fi

echo "Found app bundle: $APP_BUNDLE"

# Get version from package.json
VERSION=$(node -p "require('$APP_DIR/package.json').version")
echo "Version: $VERSION"

# Clean and create build directory
rm -rf "$PKG_BUILD_DIR"
mkdir -p "$PKG_BUILD_DIR/payload"
mkdir -p "$PKG_BUILD_DIR/scripts"

# Copy the postinstall script into the pkg scripts directory
cp "$SCRIPT_DIR/postinstall.sh" "$PKG_BUILD_DIR/scripts/postinstall"
chmod +x "$PKG_BUILD_DIR/scripts/postinstall"

# Copy the .app bundle into the scripts directory so postinstall can find it
echo "Copying app bundle into pkg scripts directory..."
cp -R "$APP_BUNDLE" "$PKG_BUILD_DIR/scripts/Coday.app"

# Re-sign the .app after copying (copying breaks the original signature)
APP_SIGNING_IDENTITY="Developer ID Application: BIZNET.IO (7DPGXLTDQS)"
if security find-identity -v | grep -q "$APP_SIGNING_IDENTITY"; then
    echo "Re-signing app bundle after copy..."
    codesign --force --deep --sign "$APP_SIGNING_IDENTITY" \
        --options runtime \
        --entitlements "$APP_DIR/macos/entitlements.mac.plist" \
        "$PKG_BUILD_DIR/scripts/Coday.app"
    echo "App bundle re-signed"
else
    echo "Warning: App signing identity not found, skipping re-sign"
fi

# Build component package with NO payload — the postinstall script handles app installation
echo "Building component package..."
pkgbuild \
    --nopayload \
    --scripts "$PKG_BUILD_DIR/scripts" \
    --identifier "com.whoz.coday" \
    --version "$VERSION" \
    "$PKG_BUILD_DIR/CodayComponent.pkg"

# Create distribution XML
cat > "$PKG_BUILD_DIR/distribution.xml" << EOF
<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="2">
    <title>Coday Desktop</title>
    <welcome file="welcome.html" mime-type="text/html"/>
    <options customize="never" require-scripts="false" hostArchitectures="x86_64,arm64"/>
    <choices-outline>
        <line choice="default">
            <line choice="com.whoz.coday"/>
        </line>
    </choices-outline>
    <choice id="default"/>
    <choice id="com.whoz.coday" visible="false">
        <pkg-ref id="com.whoz.coday"/>
    </choice>
    <pkg-ref id="com.whoz.coday" version="$VERSION" onConclusion="none">CodayComponent.pkg</pkg-ref>
</installer-gui-script>
EOF

# Create welcome HTML
cat > "$PKG_BUILD_DIR/welcome.html" << EOF
<html>
<body>
<h1>Welcome to Coday Desktop</h1>
<p>This installer will:</p>
<ul>
<li>Install Coday Desktop to your Applications folder</li>
<li>Install required dependencies (Homebrew, Node.js, tmux) if not already present</li>
</ul>
<p>Click Continue to proceed.</p>
</body>
</html>
EOF

# Build product archive
echo "Building product archive..."
productbuild \
    --distribution "$PKG_BUILD_DIR/distribution.xml" \
    --resources "$PKG_BUILD_DIR" \
    --package-path "$PKG_BUILD_DIR" \
    "$PKG_BUILD_DIR/Coday-${VERSION}-unsigned.pkg"

# Sign the package if identity is available
SIGNING_IDENTITY="Developer ID Installer: BIZNET.IO (7DPGXLTDQS)"
PKG_OUTPUT="$RELEASE_DIR/Coday-${VERSION}.pkg"

if security find-identity -v | grep -q "$SIGNING_IDENTITY"; then
    echo "Signing package with: $SIGNING_IDENTITY"
    productsign \
        --sign "$SIGNING_IDENTITY" \
        "$PKG_BUILD_DIR/Coday-${VERSION}-unsigned.pkg" \
        "$PKG_OUTPUT"
    echo "Signed package: $PKG_OUTPUT"

    # Notarize the signed package if Apple credentials are available
    if [ -n "$APPLE_ID" ] && [ -n "$APPLE_APP_SPECIFIC_PASSWORD" ]; then
        echo "Notarizing package..."
        xcrun notarytool submit "$PKG_OUTPUT" \
            --apple-id "$APPLE_ID" \
            --password "$APPLE_APP_SPECIFIC_PASSWORD" \
            --team-id "7DPGXLTDQS" \
            --wait
        echo "Stapling notarization ticket..."
        xcrun stapler staple "$PKG_OUTPUT"
        echo "Package notarized and stapled: $PKG_OUTPUT"
    else
        echo "Warning: APPLE_ID or APPLE_APP_SPECIFIC_PASSWORD not set, skipping notarization"
    fi
else
    echo "Warning: Signing identity not found, creating unsigned package"
    cp "$PKG_BUILD_DIR/Coday-${VERSION}-unsigned.pkg" "$PKG_OUTPUT"
fi

# Cleanup build directory
rm -rf "$PKG_BUILD_DIR"

echo "Package created: $PKG_OUTPUT"

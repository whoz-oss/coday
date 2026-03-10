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

# Create the postinstall script that installs the app AND runs dependency setup
cat > "$PKG_BUILD_DIR/scripts/postinstall" << 'POSTINSTALL_SCRIPT'
#!/bin/bash
set -e

LOG_FILE="/tmp/coday-postinstall.log"
exec > >(tee -a "$LOG_FILE") 2>&1

echo "=== Coday Desktop Post-Install ==="
echo "Date: $(date)"
echo "Installer temp dir: $1"
echo "Install destination: $2"

# The .app is embedded in the pkg scripts dir alongside this postinstall script
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_SRC="$SCRIPT_DIR/Coday.app"
APP_DST="/Applications/Coday.app"

# Step 1: Install the app
if [ -d "$APP_SRC" ]; then
    echo "Installing Coday.app to /Applications/..."
    if [ -d "$APP_DST" ]; then
        echo "Removing existing installation..."
        rm -rf "$APP_DST"
    fi
    cp -R "$APP_SRC" "$APP_DST"
    echo "Coday.app installed successfully"
else
    echo "ERROR: Coday.app not found at $APP_SRC"
    echo "Contents of script dir:"
    ls -la "$SCRIPT_DIR"
fi

# Step 2: Get the actual user (not root)
ACTUAL_USER="${USER}"
if [ "$EUID" -eq 0 ]; then
    ACTUAL_USER=$(stat -f "%Su" /dev/console 2>/dev/null || echo "$USER")
fi
ACTUAL_HOME=$(eval echo "~$ACTUAL_USER")

echo "Actual user: $ACTUAL_USER"
echo "Actual home: $ACTUAL_HOME"

run_as_user() {
    if [ "$EUID" -eq 0 ]; then
        sudo -u "$ACTUAL_USER" --login bash -c "$1"
    else
        eval "$1"
    fi
}

# Step 3: Install Homebrew if not present
if ! run_as_user "command -v brew" &>/dev/null; then
    echo "Installing Homebrew..."

    # Pre-create ALL Homebrew directories as root with correct ownership.
    # This allows the Homebrew installer to run without needing sudo access.
    BREW_PREFIX="/opt/homebrew"
    if [ "$(uname -m)" = "x86_64" ]; then
        BREW_PREFIX="/usr/local"
    fi

    for dir in bin etc include lib sbin share var/homebrew/linked Cellar Caskroom Frameworks; do
        mkdir -p "$BREW_PREFIX/$dir"
    done
    chown -R "$ACTUAL_USER:admin" "$BREW_PREFIX"

    # Create /etc/paths.d entry so brew is in PATH for all shells
    if [ -d /etc/paths.d ] && [ "$BREW_PREFIX" != "/usr/local" ]; then
        echo "$BREW_PREFIX/bin" > /etc/paths.d/homebrew
    fi

    # Cache sudo credentials for the user so Homebrew's sudo check passes.
    # We're already root, so we can update the user's sudo timestamp directly.
    sudo -u "$ACTUAL_USER" sudo -v 2>/dev/null || {
        # If that fails, create a sudoers.d entry temporarily
        echo "$ACTUAL_USER ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/coday-tmp-brew
        chmod 0440 /etc/sudoers.d/coday-tmp-brew
    }

    # Download and run the installer as the user.
    curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh -o /tmp/brew-install.sh
    chmod +x /tmp/brew-install.sh
    sudo -u "$ACTUAL_USER" NONINTERACTIVE=1 /bin/bash /tmp/brew-install.sh
    rm -f /tmp/brew-install.sh

    # Remove temporary sudoers entry if we created one
    rm -f /etc/sudoers.d/coday-tmp-brew

    if [ -f "$BREW_PREFIX/bin/brew" ]; then
        # Ensure brew is in user's shell profile
        run_as_user "echo 'eval \"\$($BREW_PREFIX/bin/brew shellenv)\"' >> ~/.zprofile"
        eval "$($BREW_PREFIX/bin/brew shellenv)"
    fi
    echo "Homebrew installed successfully"
else
    echo "Homebrew already installed"
fi

BREW_PATH=$(run_as_user "command -v brew" 2>/dev/null || echo "/opt/homebrew/bin/brew")

# Step 4: Install Node.js LTS if not present
if ! run_as_user "command -v node" &>/dev/null; then
    echo "Installing Node.js LTS..."
    run_as_user "$BREW_PATH install node@22"
    run_as_user "$BREW_PATH link node@22"
    echo "Node.js installed successfully"
else
    echo "Node.js already installed: $(run_as_user 'node --version')"
fi

# Step 5: Install tmux if not present
if ! run_as_user "command -v tmux" &>/dev/null; then
    echo "Installing tmux..."
    run_as_user "$BREW_PATH install tmux"
    echo "tmux installed successfully"
else
    echo "tmux already installed: $(run_as_user 'tmux -V')"
fi

echo "=== Coday Desktop Post-Install Complete ==="
exit 0
POSTINSTALL_SCRIPT
chmod +x "$PKG_BUILD_DIR/scripts/postinstall"

# Copy the .app bundle into the scripts directory so postinstall can find it
echo "Copying app bundle into pkg scripts directory..."
cp -R "$APP_BUNDLE" "$PKG_BUILD_DIR/scripts/Coday.app"

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

if security find-identity -v -p basic | grep -q "$SIGNING_IDENTITY"; then
    echo "Signing package with: $SIGNING_IDENTITY"
    productsign \
        --sign "$SIGNING_IDENTITY" \
        "$PKG_BUILD_DIR/Coday-${VERSION}-unsigned.pkg" \
        "$PKG_OUTPUT"
    echo "Signed package: $PKG_OUTPUT"
else
    echo "Warning: Signing identity not found, creating unsigned package"
    cp "$PKG_BUILD_DIR/Coday-${VERSION}-unsigned.pkg" "$PKG_OUTPUT"
fi

# Cleanup build directory
rm -rf "$PKG_BUILD_DIR"

echo "Package created: $PKG_OUTPUT"

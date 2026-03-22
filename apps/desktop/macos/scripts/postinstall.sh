#!/bin/bash
# Coday Desktop — PKG postinstall script
# This script is executed by the macOS PKG installer after extracting the payload.
# It runs as root. It can also be run directly for testing:
#   sudo bash apps/desktop/macos/scripts/postinstall.sh
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
    echo "WARNING: Coday.app not found at $APP_SRC (expected when running in test mode)"
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

# Accept Xcode license if needed (we're root, so this works non-interactively).
# Homebrew and other CLI tools will refuse to run if the license is pending.
if xcodebuild -checkFirstLaunchStatus 2>/dev/null; then
    echo "Xcode CLI tools already accepted"
else
    echo "Accepting Xcode license..."
    xcodebuild -license accept 2>/dev/null || true
    echo "Xcode license accepted"
fi

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

    # Grant temporary sudo access to the user so Homebrew's internal sudo check passes.
    # This is needed even though directories are pre-created, because the Homebrew
    # installer script validates sudo access early (before it actually needs it).
    # Using a sudoers.d entry is more reliable than sudo -v for non-admin users.
    echo "$ACTUAL_USER ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/coday-tmp-brew
    chmod 0440 /etc/sudoers.d/coday-tmp-brew

    # Download and run the installer as the user.
    curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh -o /tmp/brew-install.sh
    chmod +x /tmp/brew-install.sh
    sudo -u "$ACTUAL_USER" NONINTERACTIVE=1 /bin/bash /tmp/brew-install.sh
    rm -f /tmp/brew-install.sh

    # Remove temporary sudoers entry
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

# Step 4: Install Node.js LTS if not present or too old (need >= 22)
NODE_BIN=$(run_as_user "which node 2>/dev/null" || true)
if [ -n "$NODE_BIN" ]; then
    NODE_VERSION=$(run_as_user "$NODE_BIN --version 2>/dev/null" | sed 's/^v//' || echo "0")
    NODE_MAJOR=$(echo "$NODE_VERSION" | cut -d. -f1)
else
    NODE_VERSION="0"
    NODE_MAJOR="0"
fi
if [ "$NODE_MAJOR" -ge 22 ] 2>/dev/null; then
    echo "Node.js already installed with suitable version: v$NODE_VERSION ($NODE_BIN)"
else
    if [ "$NODE_MAJOR" -gt 0 ] 2>/dev/null; then
        echo "Node.js v$NODE_VERSION found at $NODE_BIN but too old (need >= 22), installing..."
    else
        echo "Node.js not found, installing..."
    fi
    run_as_user "$BREW_PATH install node@24"
    run_as_user "$BREW_PATH link --overwrite node@24"
    echo "Node.js installed successfully"
fi

# Step 5: Install ripgrep if not present
if ! run_as_user "command -v rg" &>/dev/null; then
    echo "Installing ripgrep..."
    run_as_user "$BREW_PATH install ripgrep"
    echo "ripgrep installed successfully"
else
    echo "ripgrep already installed: $(run_as_user 'rg --version' | head -1)"
fi

echo "=== Coday Desktop Post-Install Complete ==="
exit 0

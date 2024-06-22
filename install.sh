#!/bin/bash

# Check and install Homebrew if not installed
if ! command -v brew &> /dev/null
then
    echo "Homebrew not found. Installing Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi

# Install ripgrep
if ! command -v rg &> /dev/null
then
    echo "Installing ripgrep..."
    brew install ripgrep
fi
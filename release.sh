#!/bin/bash

# Coday Release Script
# Simple automation of the release process

set -e  # Exit on any error

# 1. Fetch tags from remote, forcing the operation
echo "Fetching tags from remote server..."
git fetch --tags --force

# 2. Create release branch from remote master (dropping any local commits)
echo "Fetching latest master from remote..."
git fetch origin master
echo "Creating chore/release branch from origin/master..."
git checkout -B "chore/release" origin/master

# 3. Run nx release
# /!\ WARNING, interactive step /!\
echo "Running nx release --skip-publish..."
nx release --skip-publish

# 4. Push branch to remote server
echo "Pushing chore/release branch to remote server..."
git push origin "chore/release"

# 5. Open link to create PR
echo "Opening link to create Pull Request..."
repo_url=$(git config --get remote.origin.url | sed 's/\.git$//' | sed 's/git@github.com:/https:\/\/github.com\//')
pr_url="${repo_url}/compare/chore/release?expand=1"
echo "PR link: $pr_url"

# Automatically open link (works on macOS and Linux)
if command -v open >/dev/null 2>&1; then
    open "$pr_url"
elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$pr_url"
else
    echo "Cannot automatically open link. Please copy-paste the URL above into your browser."
fi

echo "Release prepared successfully!"
#!/bin/bash

# Define the base directory
BASE_DIR="$HOME/.coday"
PROJECTS_DIR="$BASE_DIR/projects"
USERNAME=$(whoami)
SANITIZED_USERNAME=$(whoami | tr '.' '_')
USER_DIR="$BASE_DIR/users/$SANITIZED_USERNAME"

echo "Starting Coday configuration migration..."

# Create the projects and user directories if they don't exist
echo "Creating directories: $PROJECTS_DIR and $USER_DIR"
mkdir -p "$PROJECTS_DIR"
mkdir -p "$USER_DIR"

# Move project directories to the projects folder
echo "Migrating project directories..."
for dir in "$BASE_DIR"/*; do
    if [ -d "$dir" ] && [[ $(basename "$dir") != "projects" ]] && [[ $(basename "$dir") != "users" ]]; then
        project_name=$(basename "$dir")
        echo "  Migrating project: $project_name"
        mv "$dir" "$PROJECTS_DIR/"
    fi
done

# Add username to thread files
echo "Adding username to thread files..."
node "$(dirname "$0")/add-username-to-threads.js" "$PROJECTS_DIR" "$USERNAME"

# Migrate user configuration files, specifically changing .yml to .yaml for user config
echo "Migrating user configuration files..."
if [ -f "$BASE_DIR/user.yml" ]; then
    echo "  Renaming user.yml to user.yaml"
    mv "$BASE_DIR/user.yml" "$USER_DIR/user.yaml"
fi

# Move remaining configuration files
for ext in yml yaml; do
    for file in memories.$ext; do
        if [ -f "$BASE_DIR/$file" ]; then
            echo "  Moving $file to user directory"
            mv "$BASE_DIR/$file" "$USER_DIR/"
        fi
    done
done

node "$(dirname "$0")/copy-user-ai-providers-to-projects.js"

echo "Migration complete."
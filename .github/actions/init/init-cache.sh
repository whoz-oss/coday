#!/bin/bash

function getSlug {
  local name=$1
  # Step 1: Convert to lowercase
  local nameLower=$(echo "$name" | tr '[:upper:]' '[:lower:]')

  # Step 2: Replace all characters except 0-9 and a-z with a hyphen
  local slug=$(echo "$nameLower" | sed 's/[^a-z0-9]/-/g')

  # Step 3: Remove leading and trailing hyphens
  slug=$(echo "$slug" | sed 's/^-//' | sed 's/-$//')
  echo "$slug"
}

function getTargetBranchCacheFolderName {
  local targetBranchNameSlug=$(getSlug "$GITHUB_BASE_REF")
  if [ -d "$BRANCHES_CACHE_FOLDER/$SCHEDULED_VALIDATE_PREFIX$targetBranchNameSlug" ]; then
    echo "$BRANCHES_CACHE_FOLDER/$SCHEDULED_VALIDATE_PREFIX$targetBranchNameSlug"
  else
    echo "$BRANCHES_CACHE_FOLDER/$targetBranchNameSlug"
  fi
}

function initializeYarnCache {
  cd $WORKING_DIRECTORY

  local folder=$1
  local folderWithWorkingDirectory=$WORKING_DIRECTORY/$1

  local yarnChecksum=yarn-$(sha1sum "$folderWithWorkingDirectory/yarn.lock" | awk '{print $1}')
  local yarnCacheFolder="$FRAMEWORKS_CACHE_FOLDER/$yarnChecksum"

  symlink_target=$(readlink "$folderWithWorkingDirectory/node_modules")

  if [ "$symlink_target" != "$yarnCacheFolder/node_modules" ]; then
    echo "yarn cache for ${folder:-"/"} is empty, initializing it..."
    cd $folderWithWorkingDirectory

    mkdir -p $yarnCacheFolder/node_modules
    ln -sfn $yarnCacheFolder/node_modules node_modules

    if [ "$folder" == "frontend" ]; then
      yarn decrypt-env
    fi
  else
    echo "yarn cache for ${folder:-"/"} is already present, skipping init."
  fi

  cd $folderWithWorkingDirectory
  yarn install --frozen-lockfile --no-progress --ignore-engines

  echo "yarn cache for ${folder:-"/"} is stored in $yarnCacheFolder"
  echo "YARN_CACHE_USED_AT=$(date +%F)" > $yarnCacheFolder/$INIT_REPOSITORY_PIPELINE_ID_ENV_FILE
}

function initializeFrameworkCache {
  cd $WORKING_DIRECTORY

  local folder=$1
  local folderWithWorkingDirectory=$WORKING_DIRECTORY/$1
  local framework=$2
  if [ ! -d "$folderWithWorkingDirectory/$framework" ]; then
    if [ $GITHUB_BASE_REF ]; then
      local targetBranchCacheFolder=$(getTargetBranchCacheFolderName)
      if [ -d "$targetBranchCacheFolder/$folder/$framework" ]; then
        echo "$framework cache for target branch $GITHUB_BASE_REF in folder $folder exists, copying it..."
        cp -dpR "$targetBranchCacheFolder/$folder/$framework" "$folderWithWorkingDirectory/"
      fi
    else
      # Always create the $framework directory if it is not already present
      echo "$framework cache for $folder is empty, initializing it..."
      mkdir -p "$folderWithWorkingDirectory/$framework"
    fi
  else
    echo "$framework cache in folder $folder exists, skipping init..."
  fi
  if [ $framework = ".nx" ]; then
    cd $folderWithWorkingDirectory
    pwd
    echo "NX_RESET" $NX_RESET
    if [ "$NX_RESET" == "true" ]; then
      echo "nx reset cache requested"
      yarn nx reset
    fi
    yarn nx graph --verbose --file=$folderWithWorkingDirectory/.nx/workspace-data/project-graph.json
  fi
}

initializeYarnCache
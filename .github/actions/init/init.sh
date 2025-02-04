#!/bin/bash

echo "Init repo"
git config --global user.email "${GITHUB_ACTOR}@users.noreply.github.com"
git config --global user.name "${GITHUB_ACTOR}"
git config --global init.defaultBranch "${GITHUB_REF_NAME}"
export GIT_DISCOVERY_ACROSS_FILESYSTEM=true

if [ -f "$WORKING_DIRECTORY/$INIT_REPOSITORY_PIPELINE_ID_ENV_FILE" ]; then
  source $WORKING_DIRECTORY/$INIT_REPOSITORY_PIPELINE_ID_ENV_FILE
  if [ $INIT_REPOSITORY_PIPELINE_ID == $GITHUB_RUN_ID ]; then
    echo "Job has been manually re-run, removing repository cache"
    cd $WORKING_DIRECTORY/..
    rm -Rf $WORKING_DIRECTORY/
    mkdir -p $WORKING_DIRECTORY
    cd $WORKING_DIRECTORY
  else
    echo "Job has been automatically executed, keeping repository cache"
  fi
else
  echo "No init repository pipeline id variable found"
fi

if [ "$(git remote | grep origin)" != "origin" ]; then
  echo 'repository cache is empty, initializing it...'
  git clone "https://github.com/${GITHUB_REPOSITORY}.git" -b "${GITHUB_REF_NAME}" .
  git config merge.directoryRenames false
  git fetch --tags --force
else
  echo 'repository cache is already present, updating sources...'
  git fetch --tags --force
  git reset --hard origin/$GITHUB_REF_NAME
fi

echo "INIT_REPOSITORY_PIPELINE_ID=$GITHUB_RUN_ID" > $WORKING_DIRECTORY/$INIT_REPOSITORY_PIPELINE_ID_ENV_FILE

### For Pull Request workflows in GitHub Actions, we need to test the merge result
### This is similar to GitLab's merged results pipelines
if [ $GITHUB_BASE_REF ]; then
  sh $WORKING_DIRECTORY/.github/actions/init/init-merge-result-pipeline.sh
fi

### Never init cache for tag pipelines that are only triggered to promote a rc tag to a release tag
if [ "$GITHUB_REF_TYPE" != "tag" ]; then
  sh $WORKING_DIRECTORY/.github/actions/init/init-cache.sh
fi

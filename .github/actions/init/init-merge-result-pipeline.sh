#!/bin/bash

echo "Merge $GITHUB_BASE_REF into $WORKING_DIRECTORY to allow a merge result pipeline"

# attempt to merge
git merge --no-commit --no-ff origin/$GITHUB_BASE_REF

# if merge conflict detected
if [ $? -ne 0 ]; then
  echo "################################################################################################"
  echo "################################################################################################"
  echo "################################################################################################"
  echo ""
  echo "Merge conflict detected:"
  echo "Resolve them by merging $GITHUB_BASE_REF into $GITHUB_HEAD_REF and push changes."
  echo ""
  echo "################################################################################################"
  echo "################################################################################################"
  echo "################################################################################################"

  echo "Resetting and make the pipeline fail to handle conflicts..."

  git merge --abort

  echo "Add a comment to PR to notify assignee"
  ADD_THREAD=$(curl --fail --output "/dev/null" --silent --show-error --write-out "HTTP response: ${http_code}\n\n" \
    --data "{\"body\": \" :warning: Conflicts detected, resolve them by merging \`${GITHUB_BASE_REF}\` into \`${GITHUB_HEAD_REF}\` and then push changes.\"}" \
    --header "Content-Type: application/json" \
    --header "Authorization: token $GITHUB_TOKEN" \
    --request POST \
    "https://api.github.com/repos/${GITHUB_REPOSITORY}/issues/${GITHUB_EVENT_NUMBER}/comments")

  exit 1
else
  echo "No conflicts, continue pull request pipeline"
fi
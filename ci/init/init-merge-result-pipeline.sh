#!/bin/bash

 echo "Merge $CI_MERGE_REQUEST_TARGET_BRANCH_NAME into $WORKING_DIRECTORY to allow a merge result pipeline"

# attempt to merge
git merge --no-commit --no-ff origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME

# if merge conflict detected
if [ $? -ne 0 ]; then
  echo "################################################################################################"
  echo "################################################################################################"
  echo "################################################################################################"
  echo ""
  echo "Merge conflict detected:"
  echo "Resolve them by merging $CI_MERGE_REQUEST_TARGET_BRANCH_NAME into $CI_MERGE_REQUEST_SOURCE_BRANCH_NAME and push changes."
  echo ""
  echo "################################################################################################"
  echo "################################################################################################"
  echo "################################################################################################"

  echo "Resetting and make the pipeline fail to handle conflicts..."

  git merge --abort

  echo "Add a note on overview page to notify assignee"
  ADD_THREAD=$(curl --fail --output "/dev/null" --silent --show-error --write-out "HTTP response: ${http_code}\n\n" \
    --data "{\"body\": \" :warning: Conflicts detected, resolve them by merging \`${CI_MERGE_REQUEST_TARGET_BRANCH_NAME}\` into \`${CI_MERGE_REQUEST_SOURCE_BRANCH_NAME}\` and then push changes.\"}" \
    --header "Content-Type: application/json" \
    --header "PRIVATE-TOKEN: $GITLAB_CI_AUTO_MERGE_ACCESS_TOKEN" \
    --request POST \
    "https://gitlab.com/api/v4/projects/${CI_PROJECT_ID}/merge_requests/${CI_MERGE_REQUEST_IID}/notes")

  exit 1
else
  echo "No conflicts, continue merge request pipeline"
fi

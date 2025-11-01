# Coday Version Release

1. From `master` create a new branch, example: `chore/release`.

    We will use Nx to do the changes to check the current version on the NPM registry, change the changelog, add the tag thanks to the version type you want to publish and lastly operate the commit.


2. Prepare the release by running below command:
    ```bash
    nx release --skip-publish
    ```
3. Push the branch & the tag

    > [!IMPORTANT]
    > You must push the tag, otherwise the release won't published to the NPM registry.

## Local troubleshooting

### Issues with tags
`git fetch --tags --force` to get from remote

`git tag -l | xargs git tag -d` to remove existing local tags

`git tag -l` to list the local tags

# Coday Version Release

🚧WIP...

On `master`, take care to get the last tags :

`git tag -l | xargs git tag -d` to remove existing local tags

`git fetch --tags` to get from remote

`git tag -l` to list the local tags


To let nx build the release (changelog, tag, etc...)
Publishing is to be done by github action
```bash
yarn run nx release --skip-publish
```
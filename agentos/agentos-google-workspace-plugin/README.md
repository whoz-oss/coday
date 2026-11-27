# AgentOS Google Workspace plugin

Curated, default-deny, read-only Drive, Calendar and Gmail tools.

See [governance documentation](../docs/google-workspace-governance.md) for the exact policy,
capability limits, native OAuth configuration, bypass caveats and deployment steps.

Requires the updated AgentOS service and the deployed `agentos-http-plugin` dependency.
Build and test with Java 25: `../gradlew clean build` from this directory.

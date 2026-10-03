# W6b Factory cleanup and port migration

This change finishes the transition away from the removed Node run service and moves Factory consumers from port `3141` to the Kotlin service on `8141`.

## What changed

- Removed the obsolete run-entry configuration from `factory-service/src/main/resources/application.yml` and `factory-forge-plugin/.../ForgeProperties.kt`. The Forge plugin no longer exposes or reads `FACTORY_RUN_ENTRY`; its `pnpm nx` oracle invocation was left unchanged.
- Removed the legacy run paths from `factory-service/openapi/factory-openapi.yaml`, including list/launch/detail, stop, and review-gate operations whose IDs contained `LegacyRun`.
- Updated Factory API defaults and test fixtures to `8141` in `apps/client/proxy.conf.json`, `libs/integration/src/lib/factory.tools.test.ts`, `libs/integration/src/lib/factory.tools.node-test.ts`, `libs/model/src/lib/project-description.ts`, the AgentOS bridge configuration and its tests, and the Forge integration scripts/configuration.
- Replaced remaining operational hints that pointed to `node factory/dashboard/server.mjs` with instructions to run `factory-service` (Kotlin/Spring Boot) on port `8141`.
- Removed the legacy URL helper from `factory/dashboard/js/views/run-launch.mjs` and updated related runtime wording in `factory-verification-core` and the Forge Jira error guidance.
- Updated `factory/README.md` to describe `factory/` as the static cockpit and optional local infrastructure directory. `factory-service` is documented as the sole Kotlin/Spring Boot control plane and DAG runtime, serving `/cockpit` on `:8141`.
- Updated the MinIO/infra documentation endpoint to `:8141`.
- Added the W6b targeted-cleanup specification at `specs/89449636_w6b_finition_targeted_cleanup.md`.

## Files carrying the change

The captured diff changes these files:

- `agentos/agentos-factory-bridge-plugin/src/main/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryBridgeConfig.kt`
- `agentos/agentos-factory-bridge-plugin/src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryPublishProjectionToolSpec.kt`
- `agentos/agentos-factory-bridge-plugin/src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/FactorySubmitStepResultToolSpec.kt`
- `agentos/agentos-factory-bridge-plugin/src/test/kotlin/io/whozoss/agentos/plugins/factorybridge/FactoryTestFixtures.kt`
- `apps/client/proxy.conf.json`
- `factory-forge-plugin/src/main/kotlin/io/whozoss/factory/forge/config/ForgeProperties.kt`
- `factory-forge-plugin/src/main/kotlin/io/whozoss/factory/forge/port/JiraClient.kt`
- `factory-service/openapi/factory-openapi.yaml`
- `factory-service/src/main/resources/application.yml`
- `factory-verification-core/src/main/kotlin/io/whozoss/factory/verification/registry/RunRegistry.kt`
- `factory-verification-core/src/test/kotlin/io/whozoss/factory/verification/registry/RunRegistryTest.kt`
- `factory/README.md`
- `factory/dashboard/js/views/run-launch.mjs`
- `factory/infra/README.md`
- `forge_bmad/coday/integrations/PROJECT_SCRIPTS.yaml`
- `forge_bmad/coday/scripts/forge-factory-launch.ts`
- `forge_bmad/coday/scripts/forge-gate-run.ts`
- `forge_bmad/coday/scripts/forge-gate2-record.ts`
- `forge_bmad/coday/scripts/forge-workflow-sync.ts`
- `libs/integration/src/lib/factory.tools.node-test.ts`
- `libs/integration/src/lib/factory.tools.test.ts`
- `libs/model/src/lib/project-description.ts`
- `specs/89449636_w6b_finition_targeted_cleanup.md`

## Use and verification

Run `factory-service` with `./gradlew bootRun`, then use the cockpit at `http://127.0.0.1:8141/cockpit`. Consumers can override the Forge/bridge URLs through their existing environment variables, whose defaults now target `8141`.

The captured diff does not include command output for the requested grep or Gradle compilation, so their pass/fail status is not evidenced here. The intended compile check is:

```bash
cd factory-service
./gradlew compileKotlin compileTestKotlin --rerun-tasks
```

For the active-code cleanup check, search for `run.mjs`, `LegacyRun`, `factory/dashboard/server.mjs`, and `3141` while excluding generated/build output and archived specs/docs as applicable.

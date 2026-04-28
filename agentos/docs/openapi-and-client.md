# OpenAPI & Client Generation

## Spec Generation

The OpenAPI spec is generated from Spring MVC + Swagger annotations at build time using `springdoc-openapi`:

```bash
nx run agentos-service:generate-openapi-spec
# or
cd agentos && ./gradlew :agentos-service:generateOpenApiDocs --no-configuration-cache
```

Output: `agentos/openapi/agentos-openapi.yaml`

The SSE endpoint (`GET /api/cases/{caseId}/events`) is tagged `sse` and excluded from the generated client — consume it with the browser `EventSource` API directly.

## Client Generation

The TypeScript Angular client is generated from the spec:

```bash
nx run agentos-service:regenerate
# runs generate-openapi-spec first, then agentos-api-client:generate-client
```

Generated files land in the `agentos-api-client` project (Angular library). Do not edit generated files — they are overwritten on each regeneration.

## Checking the Spec is Up to Date

```bash
nx run agentos-service:check-openapi-spec
```

This runs `agentos-service/check-openapi-spec.sh`, which regenerates the spec and diffs it against the committed `agentos-openapi.yaml`. CI fails if they diverge.

## Customising the Generated Client

Place hand-written code in `src/custom/` inside the `agentos-api-client` project. That directory is not touched by the generator. Use it for SSE wrappers, request interceptors, or any logic that cannot be expressed in the OpenAPI spec.

## Adding a New Endpoint to the Client

1. Add the controller method with Spring MVC + Swagger annotations.
2. Run `nx run agentos-service:regenerate`.
3. The new service method appears in the generated client automatically.

## Rules

- **Never edit `agentos-openapi.yaml` by hand.** It is always generated — manual edits are overwritten on the next `generate-openapi-spec` run and will cause CI to fail.
- **Use Swagger/springdoc annotations** (`@Schema`, `@Operation`, `@Parameter`, `@ApiResponse`, …) on the Kotlin controller and resource classes to control what the spec emits. This is the only supported way to customise the spec.
- **Never edit generated files** under `agentos-api-client/src/lib/` — they are overwritten on each `regenerate` run.
- **Avoid custom springdoc `OpenApiCustomizer` beans** unless there is no annotation-based alternative. If a customizer is unavoidable, document the reason in the bean's KDoc.

### Known springdoc limitation: nullable Map values

springdoc-openapi does not emit `nullable: true` on `additionalProperties` for Kotlin `Map<K, V?>` types (nullable generics). When a map value is intentionally nullable on the backend (e.g. `Map<String, List<String>?>?`), the generated TS type will declare `Array<string>` without `| null`. The frontend workaround is a documented `null as any` cast with an explanatory comment referencing this limitation.

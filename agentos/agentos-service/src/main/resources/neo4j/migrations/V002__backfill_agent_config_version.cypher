// Backfill @Version on AgentConfig nodes created before the version field was introduced.
// Spring Data Neo4j's optimistic-locking check generates MATCH WHERE version = ?
// which fails if the property is absent. Setting version = 0 makes existing nodes
// compatible without affecting any application logic.
// WHERE a.version IS NULL makes this statement fully idempotent.

MATCH (a:AgentConfig) WHERE a.version IS NULL
SET a.version = 0;

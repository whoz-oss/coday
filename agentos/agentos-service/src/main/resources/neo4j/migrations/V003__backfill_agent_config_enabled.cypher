// Backfill enabled=false on AgentConfig nodes created before the enabled field was introduced.
// WHERE a.enabled IS NULL makes this statement fully idempotent.

MATCH (a:AgentConfig) WHERE a.enabled IS NULL
SET a.enabled = false;

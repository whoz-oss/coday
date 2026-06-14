// Unique constraints for AgentOS graph schema.
// Replaces the manual Neo4jSchemaInitializer ApplicationRunner.
// All statements use IF NOT EXISTS — fully idempotent.

CREATE CONSTRAINT user_group_name_namespace_unique IF NOT EXISTS
FOR (g:ActiveUserGroup) REQUIRE (g.name, g.namespaceId) IS UNIQUE;

CREATE CONSTRAINT namespace_external_id_unique IF NOT EXISTS
FOR (n:ActiveNamespace) REQUIRE n.externalId IS UNIQUE;

CREATE CONSTRAINT user_external_id_unique IF NOT EXISTS
FOR (u:ActiveUser) REQUIRE u.externalId IS UNIQUE;

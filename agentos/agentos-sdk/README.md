Index: agentos/agentos-sdk/README.md
IDEA additional info:
Subsystem: com.intellij.openapi.diff.impl.patch.CharsetEP
<+>UTF-8
===================================================================
diff --git a/agentos/agentos-sdk/README.md b/agentos/agentos-sdk/README.md
--- a/agentos/agentos-sdk/README.md	(revision a8f6b8c4e7780697c365fa87ceaad2b778cea002)
+++ b/agentos/agentos-sdk/README.md	(revision 98c620b17bf273effb3aa1862a005711c94f11b2)
@@ -8,6 +8,7 @@
- Plugin interfaces based on PF4J
- Core domain models (Agent, Capability, Context, etc.)
- Extension points for custom implementations
  +- HTTP API contracts (`api.*`) for all AgentOS REST resources — shared between the service and external consumers

**Dependencies:** Only PF4J - No Spring Boot, No Spring AI

@@ -164,6 +165,14 @@
Registered agent: my-custom-agent
 ```
 
+## HTTP API Definitions
+
+The SDK contains the canonical HTTP contract for every AgentOS REST resource under the `api.*` packages. Each package exposes a `*Api` interface describing the available operations and the DTO classes used as request bodies and responses.
+
+The service implements each `*Api` interface on its `@RestController`. External consumers (e.g. a Feign client in another Spring Boot module) implement the same interface on their client, adding their own `@FeignClient` and routing annotations — AgentOS does not prescribe the client technology.
+
+Two dependencies are declared `compileOnly` so their annotations are available on DTOs without being bundled into the JAR: **`jakarta.validation.api`** (`@NotNull`, `@Size`, etc.) and **`swagger-annotations`** (`@Schema`, `@Operation`).
+
 ## API Reference
 
 ### AgentPlugin Interface

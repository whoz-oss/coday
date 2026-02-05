# AgentOS

## C'est quoi ?

**AgentOS** = Système d'orchestration d'agents IA avec Spring Boot + Spring AI + Kotlin.

Conçu pour être réutilisé via Docker dans des produits comme Whoz (solution de staffing).

## Architecture

```
API REST (Controllers)
    ↓
Orchestrateur + Spring AI (OpenAI, Anthropic, vLLM)
    ↓
Plugins + Outils (Code Kotlin ou YAML)
```

## Concepts Clés

### Agent Registry
Découverte d'agents basée sur contextes, capacités, priorités, tags.

### Orchestrateur
Gestion de conversations multi-tours :
1. Génère une **intention** (ce que l'agent veut faire)
2. Sélectionne l'**outil** approprié
3. Génère les **paramètres**
4. Exécute et enregistre le **résultat**

### Système de Plugins
- **Code-Based** : Agents en Kotlin (type-safe)
- **Filesystem** : Agents en YAML (hot reload)

## Démarrage Rapide

```bash
# Avec agents built-in
./gradlew bootRun

# Avec plugin YAML
./run-filesystem.sh
```

API : `http://localhost:8080`

## Créer un Agent YAML

```yaml
# agents/my-agent.yaml
name: Mon Agent
description: Ce que fait l'agent
capabilities: [capability-1]
contexts: [GENERAL]
tags: [custom]
priority: 8
```

Recharger : `curl -X POST http://localhost:8080/api/plugins/filesystem-agents/reload`

## Créer un Plugin Kotlin

```kotlin
@Extension
class MyPlugin : AgentPlugin() {
    override fun getAgents(): List<Agent> = listOf(
        Agent(
            id = "my-agent",
            name = "Mon Agent",
            capabilities = listOf("custom"),
            requiredContext = listOf(ContextType.GENERAL),
            priority = 8
        )
    )
}
```

## Configuration Spring AI

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
```

## Docker

```dockerfile
FROM eclipse-temurin:17-jre-alpine
COPY build/libs/agentos-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Intégration Whoz

AgentOS peut être déployé comme service indépendant dans Whoz pour :
- **Affectation intelligente** de consultants (matching compétences/disponibilités)
- **Optimisation de planning** (maximiser l'utilisation)
- **Analyse de compétences** (identifier les gaps)
- **Reporting automatique** (génération de rapports)

Via Docker Compose :
```yaml
services:
  agentos:
    image: agentos:latest
    environment:
      - WHOZ_API_URL=http://whoz-app:8080/api
```

Plugins custom Whoz créés en Kotlin pour accéder aux données métier.

## Documentation Détaillée

- **Architecture complète** : [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- **Système de plugins** : [docs/PLUGIN_SYSTEM.md](docs/PLUGIN_SYSTEM.md)
- **Exemples** : [docs/EXAMPLES.md](docs/EXAMPLES.md)

---

**Stack** : Spring Boot 3.5 + Spring AI + Kotlin + PF4J

# Contexte Projet — Coday (WhozForge)

> Généré automatiquement par bmad-quick-dev. Mettre à jour lors de changements architecturaux majeurs.

---

## 1. Vue d'ensemble

**Coday** est un framework léger d'agents IA pour des projets scopés. Il tourne localement, s'interface avec diverses APIs et outils, et offre une expérience d'assistance contextuelle allant jusqu'à l'autonomie complète.

- **Repo** : https://github.com/whoz-oss/coday (org `whoz-oss`)
- **Package NPM** : `@whoz-oss/coday-web` (v0.94.x)
- **Monorepo** : NX 22.x, pnpm workspaces

---

## 2. Stack Technologique

### Backend (Node.js)
| Technologie | Version | Role |
|---|---|---|
| TypeScript | 5.9.x | Langage principal |
| Node.js (ESM) | — | Runtime (`"type": "module"`) |
| Express | 5.1.x | Serveur HTTP REST + SSE |
| RxJS | 7.8.x | Flux événementiels (Interactor, threads) |
| YAML | 2.8.x | Sérialisation config et threads |
| OpenAI SDK | 6.x | Client OpenAI |
| Anthropic SDK | 0.65.x | Client Anthropic |
| Google Generative AI | 0.24.x | Client Google |
| MCP SDK | 1.23.x | Model Context Protocol |
| axios | 1.12.x | HTTP client pour intégrations |

### Frontend (Angular)
| Technologie | Version | Role |
|---|---|---|
| Angular | 20.3.x | Framework UI |
| Angular Material | 20.2.x | Design system |
| RxJS | 7.8.x | Réactivité |
| marked | 16.x | Rendu Markdown |
| dompurify | 3.x | Sanitization HTML |

### AgentOS (Kotlin/Spring)
| Technologie | Version | Role |
|---|---|---|
| Kotlin | — | Langage |
| Spring Boot | 3.5 | Framework |
| Spring AI | — | Orchestration IA |
| PF4J | — | Système de plugins |
| Gradle | — | Build tool |

### Outillage
| Outil | Version | Role |
|---|---|---|
| NX | 22.2.x | Monorepo build system |
| pnpm | — | Package manager |
| Jest | 30.x | Tests unitaires TS |
| Playwright | 1.56.x | Tests E2E |
| ESLint | 9.x | Linting |
| Prettier | 3.x | Formatage |
| SWC | — | Transpilation rapide |
| esbuild | 0.25.x | Bundling server |

---

## 3. Architecture du Monorepo

```
coday/
├── apps/
│   ├── client/          # Angular SPA
│   ├── client-e2e/      # Tests Playwright
│   ├── desktop/         # Electron desktop
│   ├── server/          # Express server (Node.js)
│   └── web/             # Package NPM publishable (@whoz-oss/coday-web)
├── libs/
│   ├── model/           # Types, interfaces, classes domaine (source of truth)
│   ├── core/            # Classe Coday (orchestrateur principal)
│   ├── agent/           # AgentService, Toolbox
│   ├── handler/         # CommandHandler, clients AI (Anthropic/OpenAI/Google)
│   ├── handlers/        # Handlers spécialisés (config, looper, memory, openai, stats)
│   ├── service/         # Services métier (thread, project, user, scheduler, prompt...)
│   ├── repository/      # Persistance fichiers (threads, projets)
│   ├── mcp/             # MCP instance pool & tools factory
│   ├── integration/     # Interface IntegrationToolSet
│   ├── integrations/    # Implémentations par intégration (ai, file, git, gitlab, jira, slack, http...)
│   ├── coday-services/  # Agrégat CodayServices (DI container)
│   ├── function/        # Utilitaires fonctions
│   ├── utils/           # Utilitaires génériques
│   ├── design-system/   # Composants Angular partagés
│   ├── agentos-ui/      # UI AgentOS (routes Angular)
│   └── agentos-api-client/ # Client OpenAPI généré pour AgentOS
└── agentos/             # Sous-projet Kotlin/Spring (AgentOS)
```

### Aliases TypeScript (`@coday/*`)
Tous les imports internes utilisent des alias définis dans `tsconfig.base.json` :
- `@coday/model` -> `libs/model/src/index.ts`
- `@coday/core` -> `libs/core/src/index.ts`
- `@coday/agent` -> `libs/agent/src/index.ts`
- `@coday/handler` -> `libs/handler/src/index.ts`
- `@coday/service` -> `libs/service/src/index.ts`
- `@coday/mcp` -> `libs/mcp/src/index.ts`
- `@coday/integrations-ai` -> `libs/integrations/ai/src/index.ts`
- `@coday/integrations-file` -> `libs/integrations/file/src/index.ts`
- `@coday/integrations-git` -> `libs/integrations/git/src/index.ts`
- `@coday/integrations-http` -> `libs/integrations/http/src/index.ts`
- `@whoz-oss/agentos-ui` -> `libs/agentos-ui/src/index.ts`
- `@whoz-oss/design-system` -> `libs/design-system/src/index.ts`

---

## 4. Patterns Architecturaux Cles

### 4.1 Modele d'evenements (CodayEvent)
Tout le flux de communication repose sur un système d'événements typés :
- **`CodayEvent`** (abstract) — timestamp unique avec suffixe aléatoire 5 chars
- Types principaux : `MessageEvent`, `ToolRequestEvent`, `ToolResponseEvent`, `InviteEvent`, `AnswerEvent`, `ChoiceEvent`, `DelegationEvent`, `TextEvent`, `ErrorEvent`, `ThinkingEvent`, `SummaryEvent`, `FileEvent`
- L'`Interactor` (abstract) expose un `Subject<CodayEvent>` et des méthodes `promptText()`, `displayText()`, `error()`, etc.
- Le frontend recoit les événements via **SSE** (Server-Sent Events) sur `/api/projects/{project}/threads/{threadId}/event-stream`

### 4.2 AiThread — Gestion des conversations
- Classe `AiThread` centralise l'historique de conversation
- Sérialisation YAML/JSON via `serialize()` / constructeur
- Compaction automatique (SummaryEvent) quand `maxChars` dépassé
- Nettoyage des orphelins tool-request/response
- **Délégation** : `fork()` crée un sous-thread, `merge()` rapatrie le prix
- `DelegationEvent` = marqueur immutable de branche dans le thread parent

### 4.3 CommandHandler — Pipeline de commandes
- `CommandHandler` (abstract) : `accept()` + `handle()` sur une `CommandContext`
- `CommandContext` : contient `project`, `username`, `aiThread`, une file `commandQueue`, `stackDepth`
- `HandlerLooper` orchestre la boucle principale

### 4.4 Agent et ToolSet
- `Agent` : encapsule un `AgentDefinition` + `AiClient` + `ToolSet`
- `ToolSet` : liste de `CodayTool` (= `FunctionTool<any>`), exécutés via `run(toolRequest, thread?)`
- L'`AiThread` est passé explicitement a `ToolSet.run()` pour que les outils de délégation connaissent le thread parent correct
- `AgentService` : découverte et instanciation des agents depuis YAML ou `coday.yaml`

### 4.5 Integrations
- Chaque intégration expose des `CodayTool[]` via une factory
- Intégrations disponibles : `FILE`, `GIT`, `GIT_WORKTREE`, `GITLAB`, `JIRA`, `CONFLUENCE`, `SLACK`, `BASECAMP`, `ZENDESK`, `HTTP`, `AI` (delegate/redirect), `MCP`, `CORE`, `MEMORY`
- L'intégration `HTTP` supporte OAuth2 générique et endpoints configurables via YAML
- `MCP` : pool d'instances partagées (`McpInstancePool`), support stdio et HTTP transport

### 4.6 Configuration multi-niveaux
```
coday.yaml (projet)        # description, agents, scripts, prompts, ai providers, mcp
~/.coday/user.yml          # API keys, préférences utilisateur, overrides par projet
~/.coday/projects/{name}/  # threads, config locale
```
- `ProjectDescription` : `description`, `agents[]`, `scripts`, `prompts`, `ai[]`, `mcp`, `mandatoryDocs`, `optionalDocs`
- `UserConfig` : `ai[]` (avec apiKey), `projects{}` (overrides par projet), `bio`, `groups`
- `AgentDefinition` : `name`, `description`, `instructions`, `aiProvider`, `modelName`, `integrations{}`, `temperature`, `maxOutputTokens`

### 4.7 AgentOS (sous-systeme Kotlin)
- Service Spring Boot indépendant, exposé sur port 8123 (défaut)
- Proxifié par le serveur Node.js : `/api/agentos/*` -> `http://localhost:8123`
- Système de plugins PF4J (code Kotlin ou YAML hot-reload)
- Persistance fichiers dans `data/` (ou in-memory)

### 4.8 Frontend Angular
- Architecture **standalone components** (Angular 14+)
- Routing : `/` -> sélection projet, `/project/:name/thread/:id` -> chat principal, `/agentos` -> UI AgentOS
- Services clés : `EventStreamService` (SSE), `ProjectStateService`, `ThreadStateService`, `CodayService`
- `EventStreamService` : reconnexion automatique (3 tentatives, 2s délai), `NgZone.run()` pour les événements SSE
- Injection moderne : `inject()` (pas de constructeur pour les dépendances)

---

## 5. Conventions de Code

### TypeScript
- **Pas de point-virgule**, guillemets simples, 120 chars par ligne, `trailingComma: 'es5'`
- Mode strict TypeScript : `noImplicitAny`, `noImplicitReturns`, `noUnusedLocals`, `noUnusedParameters`, `noUncheckedIndexedAccess`
- Types de retour explicites sur les fonctions publiques
- `camelCase` variables/méthodes, `PascalCase` classes/interfaces/types, `kebab-case` fichiers
- Imports absolus avec alias `@coday/*` — jamais de chemins relatifs cross-lib
- `type: 'module'` (ESM natif)

### Angular
- Composants **standalone** (pas de NgModule)
- Injection via `inject()` plutôt que constructeur
- Styles SCSS par composant
- Séparation HTML template / TS logique / SCSS styles

### Tests
- Jest 30.x pour TS, Playwright pour E2E
- `nx test <lib-name>` ou `nx test <lib-name> --testFile=<filename>`
- `passWithNoTests: true` (défaut NX)
- Blocs descriptifs, assertions précises

### Git / Commits
- Conventional Commits (`feat:`, `fix:`, `chore:`, `refactor:`, etc.)
- Commitlint configuré
- Husky + lint-staged (prettier sur les fichiers modifiés)
- Branche par défaut : `master`

---

## 6. Commandes Essentielles

```bash
# Développement
pnpm web:dev              # Lance client (4200) + server (4100) en parallèle
pnpm client               # Angular dev server seul
pnpm server               # Express server seul (dev mode)

# Build & Test
pnpm test                 # Tous les tests NX
pnpm lint                 # Lint de tous les packages
nx test <lib>             # Tests d'une lib spécifique
nx build client           # Build Angular
nx build server           # Build Express

# AgentOS (Kotlin)
cd agentos && ./gradlew :agentos-service:test   # Tests Kotlin
cd agentos && ./gradlew bootRun                 # Démarrer AgentOS

# Publication
nx release                # Gestion de version (conventional commits)
```

---

## 7. Regles Critiques pour les Agents IA

### JAMAIS
- Utiliser des chemins relatifs cross-lib (toujours `@coday/*`)
- Modifier `libs/model/` sans valider l'impact sur tous les consumers
- Ajouter des dépendances circulaires entre libs
- Utiliser `NgModule` dans de nouveaux composants Angular (standalone uniquement)
- Auto-pusher vers remote (workflow BMAD : proposer seulement)
- Utiliser `console.log` pour le debug applicatif (utiliser `interactor.debug()`)
- Modifier `ThreadSerialized` sans adapter le constructeur `AiThread`

### TOUJOURS
- Passer l'`AiThread` explicitement a `ToolSet.run()` pour les outils de délégation
- Utiliser `interactor.error()` / `interactor.warn()` pour les erreurs/avertissements
- Sérialiser/désérialiser via `buildCodayEvent()` pour les événements persistés
- Respecter le pattern append-only pour `AiThread.messages` (pas de mutation directe)
- Déclarer les exports dans `index.ts` de chaque lib
- Utiliser `pnpm nx` (pas `npx nx`) pour les commandes NX
- Vérifier `context.stackDepth > 0` avant toute délégation
- Nettoyer les ressources dans `cleanup()` et `kill()` de la classe `Coday`

### ATTENTION
- `noUncheckedIndexedAccess` est activé : tout accès par index nécessite une vérification null
- Les événements SSE arrivent hors de la zone Angular -> toujours wrapper dans `NgZone.run()`
- Les timestamps `CodayEvent` ont un suffixe aléatoire 5 chars : utiliser `event.date` pour parser
- `McpServerConfig.noShare` = true pour les MCP avec état utilisateur (défaut: partagé)
- La configuration utilisateur (`~/.coday/user.yml`) contient les API keys — ne jamais logger

---

## 8. Structure de Données Persistees

### Thread (YAML/JSON)
```typescript
ThreadSerialized {
  id: string              // UUID
  username: string        // propriétaire (legacy)
  users: ThreadUser[]     // liste des utilisateurs
  projectId: string
  name: string
  summary: string
  createdDate: string     // ISO
  modifiedDate: string    // ISO
  price: number           // coût total USD
  starring: string[]
  messages: CodayEvent[]  // filtrés sur THREAD_MESSAGE_TYPES
  parentThreadId?: string // pour les sous-threads de délégation
  parentEventId?: string
  delegatedAgentName?: string
  delegatedTask?: string
}
```

### coday.yaml (projet)
Fichier racine de configuration du projet. Contient `description`, `agents[]`, `scripts{}`, `prompts{}`, `ai[]`, `mcp`, `mandatoryDocs`, `optionalDocs`.

---

## 9. Integrations Disponibles

| Clé | Description |
|---|---|
| `FILE` | Lecture/écriture fichiers projet |
| `GIT` | Commandes git |
| `GIT_WORKTREE` | Gestion des worktrees git |
| `GITLAB` | API GitLab |
| `JIRA` | API Jira |
| `CONFLUENCE` | API Confluence |
| `SLACK` | API Slack |
| `BASECAMP` | API Basecamp |
| `ZENDESK` | Articles Zendesk |
| `HTTP` | Endpoints HTTP configurables (OAuth2 inclus) |
| `MCP` | Serveurs MCP (stdio/HTTP) |
| `AI` / `DELEGATE` | Délégation inter-agents |
| `CORE` | Outils système Coday |
| `MEMORY` | Lecture/écriture mémoires agents |

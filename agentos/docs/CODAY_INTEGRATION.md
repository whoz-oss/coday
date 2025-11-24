# AgentOS - Intégration avec Coday

## Vue d'ensemble

AgentOS est conçu pour être intégré dans Coday via un **process spawn avec JAR embarqué**. Cette approche permet de maintenir la simplicité d'utilisation de Coday (`npx @whoz-oss/coday-web`) tout en bénéficiant des capacités d'orchestration d'AgentOS.

## Architecture d'Intégration

```
@whoz-oss/coday-web (package npm)
├── bin/coday-web.js          # Point d'entrée npx
├── dist/
│   ├── server/               # Express (minimal, proxy vers AgentOS)
│   ├── client/               # Frontend Angular
│   └── agentos/
│       └── agentos.jar       # JAR embarqué (~50-100MB)
└── package.json
```

## Workflow d'Exécution

### Commande Utilisateur
```bash
npx @whoz-oss/coday-web
```

### Séquence de Démarrage

1. **Détection Java** : Trouve l'exécutable Java (comme Electron trouve Node)
2. **Lancement AgentOS** : `java -jar agentos.jar` sur port 8080
3. **Health Check** : Attend `/actuator/health` (timeout 30s)
4. **Lancement Express** : Démarre sur port 4100
5. **Configuration Proxy** : Express proxy `/api/agentos/*` → AgentOS
6. **Ouverture Navigateur** : `http://localhost:4100`

## Implémentation Technique

### 1. Détection de Java

Réutilisation du pattern établi dans l'application Electron de Coday :

```typescript
// libs/agentos-manager.ts

function findJavaExecutable(): string | null {
  // Chemins communs Java
  const possiblePaths = [
    '/usr/bin/java',
    '/usr/local/bin/java',
    '/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java', // macOS
    process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin/java')
  ]
  
  // Essayer 'which java'
  try {
    const whichJava = execSync('which java', { encoding: 'utf8' }).trim()
    if (existsSync(whichJava)) return whichJava
  } catch {}
  
  // Fallback sur paths communs
  for (const javaPath of possiblePaths) {
    if (javaPath && existsSync(javaPath)) return javaPath
  }
  
  throw new Error(
    'Java not found. Please install Java 17+ from https://adoptium.net/'
  )
}
```

### 2. Spawn du Processus AgentOS

```typescript
export async function spawnAgentOS(options: {
  jarPath: string
  port: number
}): Promise<ChildProcess> {
  const javaPath = findJavaExecutable()
  
  const agentosProcess = spawn(javaPath, [
    '-jar',
    options.jarPath,
    `--server.port=${options.port}`
  ], {
    stdio: ['ignore', 'pipe', 'pipe']
  })
  
  // Logger stdout/stderr
  agentosProcess.stdout?.on('data', (data) => {
    console.log('[AgentOS]', data.toString())
  })
  
  agentosProcess.stderr?.on('data', (data) => {
    console.error('[AgentOS Error]', data.toString())
  })
  
  agentosProcess.on('exit', (code) => {
    console.log('[AgentOS] Process exited with code:', code)
  })
  
  return agentosProcess
}
```

### 3. Health Check

```typescript
export async function waitForAgentOS(
  healthUrl: string, 
  timeoutMs: number = 30000
): Promise<void> {
  const startTime = Date.now()
  
  while (Date.now() - startTime < timeoutMs) {
    try {
      const response = await fetch(healthUrl)
      if (response.ok) {
        console.log('[AgentOS] Health check passed')
        return
      }
    } catch {
      // Retry
    }
    
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  
  throw new Error(`AgentOS failed to start within ${timeoutMs}ms`)
}
```

### 4. Serveur Express (Proxy Minimal)

```typescript
// apps/server/src/server.ts

import express from 'express'
import { createProxyMiddleware } from 'http-proxy-middleware'
import { spawnAgentOS, waitForAgentOS } from './agentos-manager'

async function startServer() {
  // 1. Lancer AgentOS
  const agentosProcess = await spawnAgentOS({
    jarPath: path.join(__dirname, '../agentos/agentos.jar'),
    port: 8080
  })
  
  // 2. Attendre qu'AgentOS soit prêt
  await waitForAgentOS('http://localhost:8080/actuator/health')
  
  // 3. Créer Express
  const app = express()
  
  // 4. Proxy vers AgentOS
  app.use('/api/agentos', createProxyMiddleware({
    target: 'http://localhost:8080',
    changeOrigin: true,
    pathRewrite: { '^/api/agentos': '/api' }
  }))
  
  // 5. Endpoints Coday legacy (threads, messages, etc.)
  app.use('/api', codayApiRouter)
  
  // 6. Frontend Angular statique
  app.use(express.static(path.join(__dirname, '../client')))
  
  // 7. Démarrer Express
  app.listen(4100, () => {
    console.log('Coday server running on http://localhost:4100')
  })
  
  // 8. Cleanup au shutdown
  process.on('SIGTERM', () => {
    console.log('Shutting down AgentOS...')
    agentosProcess.kill()
  })
  
  process.on('SIGINT', () => {
    console.log('Shutting down AgentOS...')
    agentosProcess.kill()
    process.exit(0)
  })
}

startServer().catch(error => {
  console.error('Failed to start server:', error)
  process.exit(1)
})
```

## Build Pipeline

### Gradle Task pour Copier le JAR

```kotlin
// agentos/build.gradle.kts

tasks.register<Copy>("copyJarToCoday") {
    dependsOn("bootJar")
    from("build/libs")
    into("../apps/server/agentos")
    include("agentos-*.jar")
    rename { "agentos.jar" }
}
```

### Script Build Global

```json
// package.json (racine Coday)
{
  "scripts": {
    "build:agentos": "cd agentos && ./gradlew clean bootJar copyJarToCoday",
    "build:coday": "nx run-many --target=build --projects=client,server",
    "build": "pnpm build:agentos && pnpm build:coday"
  }
}
```

### Résultat du Build

```
apps/server/dist/
├── server.js
├── client/                # Frontend Angular
├── agentos/
│   └── agentos.jar       # JAR copié depuis agentos/build/libs/
└── ...
```

## Cas d'Usage

### 1. Développement Local

```bash
# Terminal 1 - Build AgentOS
cd agentos && ./gradlew bootRun

# Terminal 2 - Coday en mode dev
cd ../.. && pnpm web:dev
```

### 2. Package npm `@whoz-oss/coday-web`

```bash
# Utilisateur final
npx @whoz-oss/coday-web

# Tout démarre automatiquement :
# - AgentOS (port 8080)
# - Express (port 4100)
# - Navigateur s'ouvre
```

### 3. Déploiement Whoz (Docker)

```dockerfile
# Dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copier AgentOS
COPY agentos/build/libs/agentos.jar /app/agentos.jar

# Créer dossier plugins
RUN mkdir -p /app/plugins

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/agentos.jar"]
```

```yaml
# docker-compose.yml
services:
  agentos:
    image: whoz/agentos:latest
    ports:
      - "8080:8080"
    environment:
      - SPRING_AI_ANTHROPIC_API_KEY=${ANTHROPIC_KEY}
    volumes:
      - ./whoz-plugins:/app/plugins
```

## Migration Progressive

### Phase 1 : Coexistence

- Express garde tous ses endpoints actuels
- AgentOS accessible via `/api/agentos/*`
- Frontend peut appeler les deux APIs

```typescript
// Frontend Angular
// Appel Coday legacy
http.get('/api/threads')

// Appel AgentOS
http.get('/api/agentos/agents')
```

### Phase 2 : Migration Fonctionnalités

Déplacer progressivement les fonctionnalités Coday vers des plugins AgentOS :

```kotlin
// Plugin "coday-core" dans AgentOS
@Component
class ThreadManagementTool : Tool<ThreadParameter, CodayContext> {
  // Reprend la logique de apps/server/src/thread.routes.ts
}

@Component
class MessageManagementTool : Tool<MessageParameter, CodayContext> {
  // Reprend apps/server/src/message.routes.ts
}
```

### Phase 3 : Express = Proxy Pur

À terme, Express devient minimal :
- Servir le frontend statique
- Proxier vers AgentOS
- Gérer le lifecycle AgentOS

Toute la logique métier est dans AgentOS via plugins par défaut.

## Livrables

### Package npm

```json
{
  "name": "@whoz-oss/coday-web",
  "version": "1.0.0",
  "bin": {
    "coday-web": "./bin/coday-web.js"
  },
  "files": [
    "dist/",
    "bin/"
  ]
}
```

**Contenu** :
- Serveur Express minimal (proxy)
- Frontend Angular
- JAR AgentOS embarqué (~50-100MB)

**Taille totale** : ~60-120MB (acceptable pour npx avec cache)

### Docker Image

Image AgentOS seule pour déploiement Whoz avec plugins custom.

## Points d'Attention

### Dépendance Java

**Requis** : Java 17+

**Détection** :
- Chemins standards (`/usr/bin/java`, etc.)
- Variable `JAVA_HOME`
- Commande `which java`

**Erreur claire** si Java absent :
```
Java not found. Please install Java 17+ from:
https://adoptium.net/

After installation, verify with: java -version
```

### Taille du Package

- JAR AgentOS : ~50-100MB
- Package npm total : ~60-120MB
- Acceptable pour npx (téléchargement unique, cache local)

### Health Check

- Timeout généreux : 30 secondes
- Permet à Spring Boot de démarrer complètement
- Feedback utilisateur pendant l'attente

### Shutdown Propre

```typescript
// Gérer tous les signaux de shutdown
process.on('SIGTERM', () => agentosProcess.kill())
process.on('SIGINT', () => agentosProcess.kill())
process.on('exit', () => agentosProcess.kill())
```

## Avantages de cette Approche

### Pour l'Utilisateur
✅ **UX parfaite** : Une seule commande `npx`
✅ **Pas de configuration** : Java détecté automatiquement
✅ **Pas de service annexe** : Tout lance automatiquement

### Pour le Développement
✅ **Build unifié** : Un seul script build
✅ **Versions synchronisées** : AgentOS + Coday toujours compatibles
✅ **Pattern établi** : Réutilise la logique Electron existante

### Pour Whoz
✅ **Flexibilité** : Peut déployer AgentOS seul en Docker
✅ **Plugins custom** : Facile à injecter via volumes
✅ **Scalabilité** : AgentOS peut tourner séparément si besoin

### Pour l'Évolution
✅ **Migration progressive** : Pas de big bang, fonctionnalité par fonctionnalité
✅ **Express optionnel à terme** : Peut devenir juste un proxy léger
✅ **Plugins par défaut** : Fonctionnalités Coday = plugins AgentOS activés par défaut

## Prochaines Étapes

1. Créer `libs/agentos-manager.ts` avec spawn + health check
2. Modifier `apps/server/src/server.ts` pour lancer AgentOS
3. Ajouter proxy middleware vers AgentOS
4. Créer Gradle task `copyJarToCoday`
5. Script build unifié dans package.json
6. Tester en local avec `pnpm build && pnpm start`
7. Tester avec npx (publish en beta)
8. Documentation utilisateur finale

---

**Date de création** : 2025-11-24
**Statut** : Conception - À implémenter

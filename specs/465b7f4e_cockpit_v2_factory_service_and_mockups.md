# Spec: Servir Cockpit V2 via factory-service (Kotlin) et portage des maquettes dans apps/cockpit-v2

## Contexte et Objectifs

Suite à une régression introduite dans un commit précédent (backend cockpit-v2 indûment placé dans `apps/server` Express/TS et UI Angular cockpit-v2 trop générique), cette intervention vise à :
1. Annuler totalement et nettoyer toute présence de `cockpit-v2` dans `apps/server`.
2. Implémenter le service statique / SPA côté `factory-service` en Kotlin (Spring Boot), en additif sans impacter l'actuel `/cockpit`.
3. Porter fidèlement les maquettes issues de `scratch/cockpit-v2-mockups/design system factory/` dans `apps/cockpit-v2`, en remplaçant les composants génériques par le Shell, le design system (`--sf-*` tokens) et les composants de base, avec 3 routes placeholder.

---

## Plan d'Exécution Détaillé

### PARTIE 1 — Nettoyage complet de `apps/server`

#### 1.1 Fichiers à supprimer
- `apps/server/src/app/cockpit-v2/cockpit-v2.routes.ts`
- `apps/server/src/app/cockpit-v2/cockpit-v2.routes.spec.ts`
- Dossier `apps/server/src/app/cockpit-v2/`
- Spec obsolète si elle subsiste : `specs/6d1fa35a_cockpit_v2_foundations.md` (Note: vérifier et supprimer si besoin ou non requis).

#### 1.2 Retrait dans `apps/server/src/server.ts`
- Supprimer l'import `import { registerCockpitV2Routes } from './app/cockpit-v2/cockpit-v2.routes'`
- Supprimer l'appel `registerCockpitV2Routes(app)` / route wiring `/api/cockpit-v2`.

#### 1.3 Validation PARTIE 1
- Vérifier avec `grep -rn "cockpit-v2" apps/server` qu'aucune occurrence ne subsiste dans `apps/server`.
- Exécuter `pnpm nx test server` si applicable ou `pnpm nx lint server`.

---

### PARTIE 2 — Backend `factory-service` (Kotlin)

Le backend `factory-service` sert déjà l'ancien `/cockpit` via `CockpitProperties`, `CockpitAssets`, `CockpitController` et `CockpitWebConfig`.
Nous allons créer leurs équivalents stricts `CockpitV2Properties`, `CockpitV2Assets`, `CockpitV2Controller` et `CockpitV2WebConfig` en additif dans les mêmes packages Kotlin.

#### 2.1 Configuration et Résolution des Assets
- **`factory-service/src/main/kotlin/io/whozoss/factory/config/CockpitV2Properties.kt`**
  - Prefix `@ConfigurationProperties(prefix = "factory.cockpit.v2")`
  - Property `val assetsDir: String = "apps/cockpit-v2/dist/browser"` (ou fallback si `dist` contient `browser` ou direct). Note : Angular v17+ avec `@angular/build:application` génère les assets sous `dist/browser` ou `dist`. `CockpitV2Assets` cherchera `apps/cockpit-v2/dist/browser` puis `apps/cockpit-v2/dist`.

- **`factory-service/src/main/kotlin/io/whozoss/factory/web/CockpitV2Assets.kt`**
  - `@Component class CockpitV2Assets(properties: CockpitV2Properties)`
  - Résout les chemins candidats (`apps/cockpit-v2/dist/browser`, `apps/cockpit-v2/dist`).
  - Gère la sécurité anti directory traversal (`..`).
  - Propose `resolve(relativePath: String): Resource?` et `location(): Resource`.

#### 2.2 Contrôleur Spring MVC
- **`factory-service/src/main/kotlin/io/whozoss/factory/web/CockpitV2Controller.kt`**
  - `@Hidden @Controller class CockpitV2Controller(private val assets: CockpitV2Assets)`
  - Endpoint SPA fallback pour `/cockpit-v2`, `/cockpit-v2/`, et `/cockpit-v2/**`:
    - Pour les routes HTML / navigation client : renvoie `index.html` avec `contentType(MediaType.TEXT_HTML)`.
    - **Attention Règle Métier Importante** : Si la requête demande un fichier static inexistant avec une extension JS ou CSS (ex: `/cockpit-v2/main-xyz.js`, `/cockpit-v2/styles.css`), le contrôleur **NE DOIT PAS** renvoyer `index.html`, mais retourner une erreur 404 (`factoryError(404, "COCKPIT_V2_ASSET_NOT_FOUND", ...)` ou `ResponseEntity.notFound().build()`).

#### 2.3 Handler et WebConfig Statique
- **`factory-service/src/main/kotlin/io/whozoss/factory/config/CockpitV2WebConfig.kt`**
  - `@Configuration class CockpitV2WebConfig(private val assets: CockpitV2Assets)`
  - Mappe `/cockpit-v2/**` ou les sous-ressources statiques (js, css, media, assets hashés Angular) vers un `ResourceHttpRequestHandler` dédié pointant vers `assets.location()`.
  - Mappe l'extension `.js` / `.mjs` avec `application/javascript`.

#### 2.4 Test d'Intégration Kotlin (SpringBootTest / MockMvc)
- **`factory-service/src/test/kotlin/io/whozoss/factory/web/CockpitV2StaticServingIntegrationTest.kt`**
  - Valide :
    1. `GET /cockpit-v2` et `GET /cockpit-v2/sandboxes` => 200 OK `text/html` (reçoit `index.html` du build ou mock).
    2. Request vers asset `.js` inexistant (ex: `GET /cockpit-v2/non-existent.js`) => 404 Not Found (pas de 200 HTML !).
    3. `GET /cockpit` reste inchangé et continue de répondre 200 avec le cockpit V1 (`<title>Factory</title>`).

#### 2.5 Validation PARTIE 2
- Executer `./gradlew test --tests io.whozoss.factory.web.CockpitV2StaticServingIntegrationTest` dans `factory-service/`.

---

### PARTIE 3 — Portage des Maquettes dans `apps/cockpit-v2`

#### 3.1 Suppression des Fichiers Génériques Précédents
Dans `apps/cockpit-v2/src/app/` :
- Supprimer `cockpit-v2.component.ts`, `cockpit-v2.component.html`, `cockpit-v2.component.scss`, `cockpit-v2.component.spec.ts`.
- Supprimer `features/dashboard/` (comprenant `dashboard.component.*`).
- Supprimer `shared/models/cockpit-v2.model.ts`, `shared/services/cockpit-v2-api.service.*` et `shared/components/`.

#### 3.2 Implantation des Fichiers Portage Maquette
Créer / remplacer les fichiers dans `apps/cockpit-v2/src/` :

1. **`src/styles.scss`**
   - Reprendre `styles.scss` des maquettes : `@use '@angular/material' as mat;`, variables `:root` `--sf-*`, surcharge Material M3, utilitaires `.sf-*`, styles boutons.
2. **`src/index.html`**
   - Reprendre `index.html` des maquettes avec polices Google Fonts (Manrope, JetBrains Mono, Material Symbols Outlined), `<base href="/cockpit-v2/" />` et `<sf-root></sf-root>`.
3. **`src/main.ts`**
   - Bootstrap sur `ShellComponent` :
     `bootstrapApplication(ShellComponent, appConfig).catch((err) => console.error(err))`
4. **`src/app/app.config.ts`**
   - `provideZonelessChangeDetection()`, locale `fr`, `provideRouter(routes, withComponentInputBinding())`, enregistrement icônes `material-symbols-outlined`.
5. **`src/app/core/paginator-intl.fr.ts`** (nécessaire pour `FrPaginatorIntl` référencé dans `app.config.ts`).
   - Implémenter une classe simple étendant `MatPaginatorIntl` avec les libellés en français.
6. **`src/app/core/shell-state.ts`**
   - Inyectable `ShellState` avec `crumbs = signal<Crumb[]>([])`.
7. **`src/app/core/models.ts`**
   - Interfaces `Sandbox`, `RunSummary`, `SessionDetail`, `CostSummary`, `Tone`, `PhaseSegment`, etc.
8. **`src/app/core/mock-data.ts`**
   - Export de `SANDBOXES`, `RECENT_TASKS`, `SESSION_872641A8`.
9. **`src/app/core/factory.store.ts`**
   - Injectable `FactoryStore` avec signals et computeds (`sandboxes`, `activeSandboxes`, `destroyedSandboxes`, `costs`, `session()`, `destroy()`).
10. **`src/app/layout/shell.component.ts`, `.html`, `.scss`**
    - `ShellComponent` (selector: `sf-root`) avec `mat-toolbar`, fil d'Ariane (`shell.crumbs()`), pillule de coûts (`store.costs()`), slide toggle sandboxes détruites, `mat-sidenav` rail de navigation avec icônes.
11. **`src/app/shared/ui/status-chip.component.ts`** (`sf-status-chip`)
12. **`src/app/shared/ui/metric-chip.component.ts`** (`sf-metric`)
13. **`src/app/shared/ui/phase-bar.component.ts`** (`sf-phase-bar`)
14. **`src/app/shared/pipes/format.pipes.ts`** (`usd`, `duration`, `tokens`)
15. **`src/app/app.routes.ts`**
    - Configurer les routes :
      - `''` -> redirect `/sandboxes`
      - `'sandboxes'` -> `SandboxesPlaceholderComponent`
      - `'sessions/:runId'` -> `SessionPlaceholderComponent`
      - `'historique'` -> `HistoryPlaceholderComponent`
      - `'**'` -> redirect `/sandboxes`
    - Les composants placeholder seront de simples Inline Standalone Components OnPush minimalistes (ex: `<div>Sandboxes Page Placeholder</div>`) afin que les routes soient valides et compilent sans devoir importer les composants lourds (`sandboxes-page.component`, `session-page.component`, `history-page.component`) réservés pour les vagues suivantes.

#### 3.3 Configuration Angular / Nx
- **`apps/cockpit-v2/project.json`**
  - Ajuster `prefix`: `"sf"`.
  - Conserver `"baseHref": "/cockpit-v2/"`.
  - Vérifier la configuration des styles et assets.
  - S'assurer que le builder Angular sort les assets dans `apps/cockpit-v2/dist` (ou `dist/browser`).

#### 3.4 Validation PARTIE 3 & Global
- Tester la compilation Angular : `pnpm nx build cockpit-v2`
- Tester les tests unitaires Angular : `pnpm nx test cockpit-v2`
- Tester les tests backend Kotlin : `cd factory-service && ./gradlew test`

---

## Critères d'Acceptation Requis

1. `nx build cockpit-v2` compile sans erreur.
2. Le Shell (`ShellComponent` : toolbar + fil d'Ariane + pill de coûts + rail) s'affiche et s'appuie sur les mocks / signals (`FactoryStore`).
3. Les 3 routes placeholder répondent.
4. `factory-service` sert `apps/cockpit-v2/dist` sur `/cockpit-v2` (et `/cockpit-v2/**`), et renvoie 404 pour asset js/css inexistant (test Kotlin vert).
5. `/cockpit` reste totalement inchangé.
6. Aucune trace de `cockpit-v2` ne subsiste dans `apps/server`.

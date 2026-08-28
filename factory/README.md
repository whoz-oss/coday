# factory/

Orchestrator externe de validation pour le dépôt Coday.

## Ce que c'est

`factory/` est un orchestrateur léger qui exécute des workflows de validation
en combinant des commandes système (oracle) et des tours d'agents AgentOS.
Il enregistre les résultats dans un registre JSONL immuable (`runs/`).

## Pourquoi il est hors du workspace pnpm

**L'instrument ne doit pas dépendre de la santé de ce qu'il mesure.**

Si `pnpm install` est cassé, si `node_modules/` est corrompu, si le
`package.json` racine est invalide — `factory/` doit quand même fonctionner.
Il n'a aucune dépendance externe : uniquement les modules natifs Node (`fs`,
`path`, `child_process`, `crypto`) et le `fetch` global (Node 18+).

Aucun `package.json`, aucun `project.json` Nx, aucune compilation TypeScript.
Les fichiers `.mjs` sont exécutés directement par `node`.

## Lancer un workflow (livraison)

```bash
# Boucle acteur/oracle — un agent modifie, l'orchestrateur vérifie
FACTORY_NAMESPACE_ID=<id-namespace> \
FACTORY_AGENT=<nom-agent> \
FACTORY_DOMAIN=front \
FACTORY_TASK="..." \
FACTORY_SCOPE="..." \
node factory/run.mjs workflow fix-loop

# Analyste + éditeur en boucle structurée
FACTORY_NAMESPACE_ID=<id-namespace> \
FACTORY_DOMAIN=front \
FACTORY_TASK="..." \
FACTORY_SCOPE="..." \
node factory/run.mjs workflow us-loop
```

## Lancer un diagnostic (vérification de plomberie)

```bash
# Valider la chaîne (registry, oracle, client AgentOS)
FACTORY_NAMESPACE_ID=<id-namespace> \
FACTORY_AGENT=<nom-agent> \
node factory/run.mjs diagnostic agentos-smoke

# Vérifier la compilation du backend — aucun agent
node factory/run.mjs diagnostic backend-oracle-check
```

Variables d'environnement :

| Variable | Défaut | Rôle |
|---|---|---|
| `AGENTOS_URL` | `http://localhost:8124` | URL de base AgentOS |
| `FACTORY_USER` | `benjamin.valdes` | Identité d'authentification |
| `FACTORY_NAMESPACE_ID` | *(requis)* | Namespace AgentOS |
| `FACTORY_AGENT` | *(requis pour `fix-loop`)* | Nom de l'agent éditeur |
| `FACTORY_AGENT_EDITOR` | `factory-editor` | Rôle éditeur pour `us-loop` |
| `FACTORY_AGENT_ANALYST` | `factory-analyst` | Rôle analyste pour `us-loop` |
| `FACTORY_TASK` | *(requis pour `fix-loop`, `us-loop`)* | La tâche à accomplir |
| `FACTORY_SCOPE` | *(optionnel)* | Périmètre autorisé, texte libre |
| `FACTORY_DOMAIN` | `front` | Domaine de build : `back` ou `front` |

Pour obtenir `FACTORY_NAMESPACE_ID` et `FACTORY_AGENT` : ouvrir l'UI AgentOS
(`http://localhost:8124`) → Settings → Namespaces.

## Provisionner les rôles de phase

```bash
FACTORY_NAMESPACE_ID=<id-namespace> node factory/provision.mjs
```

Crée (ou met à jour) quatre choses dans le namespace, de façon idempotente :

1. **L'intégration `FACTORY_FILES`** — `FILE_ACCESS` avec `readOnly: false`, pour l'éditeur.
2. **L'intégration `FACTORY_FILES_RO`** — `FILE_ACCESS` avec `readOnly: true`, pour l'analyste.
3. **L'agent `factory-editor`** — rôle d'édition, accès en écriture.
4. **L'agent `factory-analyst`** — rôle d'analyse, lecture seule.

Les noms `FACTORY_FILES` et `FACTORY_FILES_RO` sont délibérément distincts de `FILES` :
cette dernière existe déjà dans plusieurs namespaces avec des `rootPath` différents,
et y est parfois chargée depuis le disque plutôt que depuis la base.

Le périmètre de capacité est défini dans le script plutôt que saisi à la main, parce
que c'est lui qui rend le verdict de l'orchestrateur crédible :

| Capacité | Éditeur | Analyste | Raison |
|---|---|---|---|
| `FACTORY_FILES` | accordé | — | écrire la modification, borné au dépôt local |
| `FACTORY_FILES_RO` | — | accordé | lecture seule ; l'analyste ne doit pas pouvoir implémenter |
| `PROJECT_SCRIPTS`, `BASH` | refusé | refusé | l'agent pourrait lancer le build et se juger lui-même |
| `MCP_STDIO` (dont `NX`) | refusé | refusé | processus enfant arbitraire — contourne toutes les autres restrictions |
| `GIT` | refusé | refusé | un `checkout` ou `stash` fausserait la mesure du diff |
| `QUERY_USER` | opt-out | opt-out | une question bloque le case jusqu'au timeout en run automatique |
| `subAgents` | absent | absent | `DelegationTool` parallélise sans condition |

**Pourquoi `readOnly: true` pour l'analyste est structurel, pas cosmétique** : un analyste
capable d'écrire commence à implémenter (comportement serviable par défaut d'un modèle),
et son plan devient alors la narration de ce qu'il a déjà fait — exactement ce que cette
factory existe pour ne pas faire circuler.

`fix-loop` refuse de partir si l'agent déclare des `subAgents`, s'il est désactivé ou
s'il n'existe pas — dans les trois cas le rapport liste les agents du namespace en
marquant ceux qui sont utilisables comme rôle de phase.

### La colocalisation est vérifiée avant chaque run

Le préflight compare le `rootPath` de chaque intégration `FILE_ACCESS` de l'agent avec
la racine du dépôt où tourne l'orchestrateur. L'égalité doit être exacte.

Sans cette garde, un agent peut modifier un dépôt pendant que l'oracle en compile un
autre. Le run échoue alors si le fichier visé n'existe pas des deux côtés — mais si la
tâche est réalisable dans les deux arbres, **le verdict est vert sur un travail
invisible**. C'est arrivé le 19 août 2026 avec un namespace dont l'intégration pointait
sur un autre checkout.

Le préflight est aussi **fail-closed sur l'invérifiable** : une intégration déclarée par
l'agent mais absente de l'API REST (cas des intégrations chargées depuis
`{configPath}/integrations/`) bloque le run. Son `rootPath` n'étant pas lisible, la
colocalisation ne peut pas être garantie.

### Pourquoi l'orchestrateur ne pose jamais de question

Quand `FACTORY_AGENT` ne désigne aucun agent valide, l'orchestrateur échoue au lieu de
demander un remplaçant. Un programme qui pose une question a besoin de quelqu'un pour
y répondre ; la finalité de cet outil est de tourner sans surveillance, où un point
d'arrêt interactif devient un blocage silencieux. Le rapport d'échec porte l'inventaire
des agents — relancer avec la bonne valeur coûte une commande.

## Invariants

### 1. Statut `fail` par défaut

Chaque phase est écrite immédiatement dans le registre avec `status: 'fail'`.
Elle ne devient `pass` que si la fonction `passPhase()` est explicitement
appelée. Si l'orchestrateur plante en cours de route, le registre reste
honnête : les phases non terminées apparaissent comme échouées.

### 2. L'agent ne connaît pas son oracle

L'orchestrateur — et lui seul — choisit la commande de vérité, la lance et lit son
code de sortie. Les briefs envoyés aux agents ne mentionnent jamais la commande, et
leur disent explicitement de ne pas tenter de compiler ou tester. Un acteur qui
connaît son oracle optimise pour l'oracle.

Le verdict est `exitCode === 0`, jamais une chaîne cherchée dans la sortie.

### 3. Aucune sortie de LLM dans le registre

Le registre ne contient que des faits : noms de phases, statuts, durées,
fichiers modifiés, verdicts booléens, codes de sortie, compteurs d'outils.
Jamais le texte produit par un agent. Un registre contenant du texte généré
n'est pas une preuve : il faudrait faire confiance à ce qu'on mesure.

## Workflow us-loop

### Séquence

```
preflight      code    deux rôles vérifiés + colocalisation de l'éditeur
analyse-R      agent   l'analyste (lecture seule) rend un plan JSON structuré
plan-gate-R    code    chaque fichier du plan existe-t-il sur disque ?
edit-R-T       agent   l'éditeur reçoit le plan et implémente
verify-R-T     code    oracle : exitCode === 0 ?
claims-gate-R  code    diff réel vs fichiers annoncés (fait, pas verdict)
```

Les indices R (révision) et T (tentative) sont séparés : `edit-1-2` = révision 1, tentative 2.

### Deux rôles

| Rôle | Agent | Accès fichier | Produit |
|---|---|---|---|
| Analyste | `factory-analyst` | `FACTORY_FILES_RO` (lecture seule) | plan JSON en mémoire |
| Éditeur | `factory-editor` | `FACTORY_FILES` (lecture + écriture) | modifications sur disque |

### Trois budgets

| Constante | Valeur | Signification |
|---|---|---|
| `MAX_FIX_LOOPS` | 3 | Tentatives de correction par l'éditeur avant de remonter à l'analyste |
| `MAX_REVISION_LOOPS` | 2 | Nombre de plans que l'analyste peut proposer avant échec définitif |
| `JSON_FIX_ATTEMPTS` | 2 | Reformulations demandées si le plan n'est pas un JSON valide |

### Deux gates

**plan-gate** (après analyse) : chaque chemin de `plan.files` doit exister avec `existsSync`.
Un chemin absolu ou contenant `..` est rejeté. Un plan qui cite un fichier inexistant
échoue avant qu'un seul token d'implémentation soit dépensé. Limite assumée : ce gate
ne couvre pas la création de fichiers neufs.

**claims-gate** (après un verify réussi) : compare les fichiers réellement modifiés
(accumulés depuis le début de la boucle d'édition) aux fichiers annoncés par le plan.
Un écart ne fait pas échouer le run — c'est un fait enregistré, pas une faute.

### Le plan voyage en mémoire

`runAgentTurn` retourne un champ `message`. Le workflow lit le plan depuis ce champ et
le passe au brief de l'éditeur. L'analyste étant en readOnly, il ne peut pas écrire.
Le workflow n'écrit pas non plus le plan sur disque : `snapshotDiff` le compterait
comme une écriture et fausserait la garde `wroteNothing`.

### Exemple de lancement

```bash
FACTORY_NAMESPACE_ID=<id-namespace> \
FACTORY_DOMAIN=front \
FACTORY_TASK="Ajouter un champ 'priority' dans ThreadSummary et l'exposer dans l'API thread" \
FACTORY_SCOPE="libs/model, apps/server/src" \
node factory/run.mjs us-loop
```

## Structure

```
factory/
  run.mjs              Point d'entrée : node factory/run.mjs <catégorie> <nom>
  provision.mjs        Crée/met à jour les quatre objets (2 intégrations + 2 agents)
  lib/
    agentos.mjs        Client REST AgentOS
    registry.mjs       Registre JSONL des runs
    oracle.mjs         Exécution de commandes de vérité
    domains.mjs        Commandes de compilation par domaine
    plan.mjs           Parsing et comparaison de plans (fonctions pures)
  workflows/           Workflows de livraison (production)
    fix-loop.mjs       Boucle acteur/oracle avec budget de tentatives
    us-loop.mjs        Analyste + éditeur en boucle structurée
  diagnostics/         Diagnostics de plomberie (non-livraison)
    agentos-smoke.mjs        Valider la chaîne AgentOS
    backend-oracle-check.mjs Oracle seul, aucun agent
  tests/               Scripts de test (voir tests/README.md)
    test-f7.mjs              Logique de quiescence multi-tours (unité, pur)
    test-dashboard-chronology.mjs  Chronologie Gantt du dashboard (statique)
    test-oracle.mjs          countTaskOutcomes de oracle.mjs (unité, pur)
    test-jira.mjs            extractTicketId et extractAdfText (unité, pur)
    test-review.mjs          parseReviewResult, aggregateReviews, toReviewFacts (unité)
    test-review-engine.mjs   runReview avec fakes AgentOps (unité)
    test-review-adapter.mjs  makeReviewAgentOps câblage (unité)
    test-run-dispatch.mjs    Table de dispatch de run.mjs (statique)
    test-shutdown.mjs        Terminaison gracieuse SIGTERM (unité)
    test-us-loop.mjs         parsePlan, compareClaims, buildOracleCommand (unité)
    test-shutdown-operational.sh  Test intégration SIGTERM live (opérationnel, AgentOS requis)
  runs/                Registre des runs (git-ignoré)
```

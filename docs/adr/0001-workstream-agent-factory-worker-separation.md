# ADR 0001 — Séparation Workstream Agent / Factory / worker

- Statut : **Accepté** (Phase 0 — design)
- Date : 2026-10-03
- Portée : plan de contrôle Factory (`factory-service`), pont AgentOS
  (`agentos-factory-bridge-plugin`), workers d'exécution, surface HTTP `/api/factory/**`
- Voir aussi : [`app_docs/workstream_agent_cartography_and_contracts.md`](../../app_docs/workstream_agent_cartography_and_contracts.md)

## Contexte

Le système Factory actuel mélange encore trois responsabilités au sein d'un même canal d'exécution :

1. **l'autorité d'état** — les projections de workflow, les transitions gouvernées, l'evidence, les
   interactions humaines et les attempts durables. Dans le code, cette autorité est déjà centralisée
   (`WorkflowService`, `WorkUnitEnvironmentService`, `AgentStepResultService`,
   `DurableAgentAttemptService`) derrière des stores Neo4j tenant-scopés.
2. **l'exécution** — les workers (et l'adaptateur transitoire Coday Express) qui font tourner des
   turns d'agent et produisent des résultats structurés.
3. **la supervision produit** — un futur « Workstream Agent » censé suivre un workstream, détecter
   les blocages et proposer des plans/retries.

Aujourd'hui, les tools Factory (`FACTORY__*`) sont accordés de façon largement interchangeable et
plusieurs doublons legacy (`publish_projection`, `transition_workflow`) subsistent. Cela rend floue
la frontière entre « lire », « proposer » et « écrire », et expose des identités à des composants qui
ne devraient jamais les décider.

Il faut donc fixer explicitement qui porte chaque responsabilité **avant** d'introduire le
Workstream Agent, afin d'éviter qu'il ne devienne un second plan de contrôle concurrent.

## Décision

Nous séparons strictement trois rôles autour d'une **autorité Factory unique**.

### 1. La Factory est l'autorité unique des faits

- Toute écriture d'état passe par les stores et services de `factory-service` (`WorkflowService`,
  `WorkUnitEnvironmentService`, `AgentStepResultService`, `DurableAgentAttemptService`,
  `WorkstreamService`).
- Les transitions sont **gouvernées** : validées par `WorkflowTransitionPolicy`, appliquées sous
  `expectedRevision`, avec evidence obligatoire pour tout passage à un statut de succès.
- Les identités (`workstreamId`, `workflowId`, `attemptId`, `caseId`, `namespaceId`, `actorId`,
  `organizationId`) sont résolues depuis un contexte de confiance (`TrustContext` / `ToolContext`),
  jamais depuis un input rédigé par un modèle.
- Les faits sensibles (`oracle-result`, `human-decision`) sont réservés au control-plane
  (`FACTORY_ONLY_EVIDENCE`).

### 2. Le Workstream Agent est un superviseur en lecture + propositions

- Il détient les tools de **lecture** : `get_workstream`, `list_workflows`, `get_workflow`,
  `get_step_attempts`, `get_blockers`, `get_required_human_actions`.
- Il détient les tools de **proposition sans effet d'exécution** : `propose_plan_change`,
  `request_agent_retry`, `request_human_decision`.
- Il **ne** peut pas : appliquer une transition d'exécution (`request_transition`),
  répondre à un checkpoint humain, annuler/interrompre un attempt (`interrupt_attempt`),
  publier une projection, provisionner un environnement ou manipuler le coût.
- Ses propositions sont persistées comme des faits (`pending_validation`) que la Factory valide.

### 3. Le worker exécute sous capability, dans son périmètre

- Un worker ne peut écrire que dans le périmètre de l'`attemptId`/`caseId` pour lequel une
  **capability** lui a été émise (`ResultCapabilityRepository`, token lié à
  `(attemptId, workflowId, stepId, namespaceId, caseId, agentName, briefHash)`).
- Il détient `request_transition` (step agent-owned + evidence), `record_evidence`,
  `submit_step_result`, `provision_environment` (scopé).
- Distinction **worker RO** (lecture/diagnostic uniquement) et **worker writable** (exécution +
  soumission) ; le grant est explicite via `FactoryToolGrantService`.
- Un worker ne peut jamais lever un blocker humain ni décider d'un plan.

### 4. Le human-control-plane est le seul à lever les checkpoints humains

- Seul un acteur humain authentifié (`principalType == human`, `actorId` sûr) peut répondre à une
  interaction (`reply`) ou à une question AgentOS (`agent-questions/.../answer`).
- Les autres rôles ne peuvent qu'**ouvrir** un checkpoint (`request_human_decision`), pas le résoudre.
- `interrupt_attempt`, `cost/continue`, `cost/stop` et `publish_projection` (legacy déclaratif) sont
  réservés au control-plane.

### 5. Dépréciation des doublons legacy

- `publish_projection` (tool + `PUT /projection`) est déprécié : réservé aux workflows déclaratifs ;
  refusé sur un workflow gouverné (`GOVERNED_WORKFLOW_REQUIRES_TRANSITION`).
- `transition_workflow` n'est qu'un alias de compatibilité de `request_transition`.
- L'adaptateur `FactoryTools` de `libs/integration` est transitoire et sera supprimé au profit du
  plugin AgentOS.

## Conséquences

### Positives

- **Frontière nette** entre lecture, proposition et écriture : un composant ne peut agir au-delà de
  sa capacité, même si le modèle le demande.
- **Autorité unique** : plus de second plan de contrôle ; les clients dérivent leurs actions de
  `GET /{workflowId}/actions` au lieu de recalculer l'état.
- **Auditabilité** : toute proposition de plan ou de retry est un fait persistant validé par la Factory.
- **Sécurité** : les identités viennent du contexte de confiance ; les secrets ne franchissent pas la
  frontière publique.

### Négatives / coûts

- Plus de surface de tools à maintenir et des allowlists de grant plus explicites.
- Le Workstream Agent doit attendre la validation Factory pour tout effet ; le contrôleur de
  plan-change doit être implémenté (nouveau write-path).
- La dépréciation progressive des routes/tools legacy impose une fenêtre de compatibilité.

### Invariants à préserver

- `SUCCEEDED`/`completed` n'est jamais atteignable sans evidence correspondante.
- Toute commande mutationnelle est fencée par `expectedRevision` (+ `idempotencyKey` si applicable).
- `TrustContext` absent ⇒ 401 `TRUST_CONTEXT_UNAVAILABLE` (fail closed).
- Aucun input modèle ne fournit une identité.

## Alternatives envisagées

1. **Workstream Agent avec droits d'exécution directs** — rejeté : créerait un second plan de contrôle
   concurrent et diluerait la fence de révision/l'autorité Factory.
2. **Fusionner lecture/proposition/exécution dans un seul rôle worker** — rejeté : rend impossible la
   supervision produit en lecture seule et augmente la surface de privilèges.
3. **Conserver les doublons legacy** (`publish_projection`, `transition_workflow`) comme canoniques —
   rejeté : entretient deux modèles d'écriture (déclaratif vs gouverné) et empêche l'audit unifié.
4. **Décider l'état côté client (Cockpit)** — rejeté : contredit la règle d'autorité unique et
   l'endpoint `actions` déjà en place.

## Portée de suivi (hors Phase 0)

- Implémenter les tools de lecture (§6.1) et de commande (§6.2) du document de cartographie.
- Implémenter le write-path `propose_plan_change` (`pending_validation`) côté Factory.
- Câbler les allowlists `FactoryToolGrantService` par rôle (Workstream Agent / worker RO / worker writable).
- Planifier la suppression des routes/tools legacy une fois la migration terminée.

# Architecture de la Factory

## Mission

La Factory est un instrument externe d'orchestration et de validation. Elle pilote des workflows, sollicite éventuellement AgentOS, exécute des oracles déterministes et conserve des faits. Sa crédibilité dépend de sa capacité à fonctionner lorsque le produit qu'elle mesure est partiellement ou totalement en panne.

## Principe architectural

La cible de migration est la suivante :

```text
Sources TypeScript strictes
        │
        ▼
Toolchain Factory isolée
        │ compilation / assemblage
        ▼
Artefact JavaScript ESM autonome
        │
        ▼
Node + OS + réseau/fichiers explicitement requis
```

Le runtime ne charge pas les sources TypeScript. Il ne requiert ni pnpm, ni Nx, ni compilateur, ni bundler, ni `node_modules`. La toolchain peut être installée et exécutée séparément, mais sa disponibilité n'est pas une précondition d'un run à partir d'un artefact déjà construit.

## Frontière avec le produit mesuré

Le dépôt Coday, son workspace pnpm/Nx et AgentOS/Gradle sont à l'extérieur de la frontière de confiance de la Factory. La Factory peut :

- lire et mesurer le checkout ;
- lancer des commandes oracle explicitement définies ;
- observer leurs codes de sortie et sorties bornées ;
- appeler AgentOS via HTTP ;
- écrire ses propres registres et artefacts opérationnels.

Elle ne peut pas utiliser les dépendances installées du produit pour se construire ou démarrer. Une commande oracle peut volontairement invoquer pnpm, Nx ou Gradle afin de mesurer le produit ; cela ne transforme pas ces outils en dépendances du runtime Factory. Leur panne est un résultat mesuré, pas une panne de bootstrap de l'instrument.

## Frontière avec AgentOS

AgentOS fournit des capacités distantes : création de cases, publication de messages, lecture d'événements et arrêt de cases. `factory/lib/agentos.mjs` est aujourd'hui l'adaptateur runtime principal de cette frontière.

La Factory conserve l'autorité sur les briefs, les gates, les oracles et les verdicts. AgentOS ne doit ni importer la Factory, ni lui fournir une bibliothèque runtime, ni construire son artefact. Les workflows sans agent doivent pouvoir fonctionner sans AgentOS ; ceux qui l'exigent doivent échouer de manière observable et enregistrée si le service est indisponible.

## Bounded contexts

### Runtime d'orchestration

Inclut le point d'entrée, le dispatch, les workflows et diagnostics. Il coordonne les autres contextes sans déléguer son verdict à un agent.

### Mesure déterministe

Inclut les domaines, commandes oracle, snapshots Git, comparaisons et gates déterministes. Le code de sortie est l'autorité du verdict oracle.

### Collaboration AgentOS

Inclut le client HTTP, les préflights, les adaptateurs de revue et la gestion des cases actifs. Ce contexte traduit un protocole externe sans absorber les responsabilités d'AgentOS.

### Preuves et observabilité

Inclut le registre append-only, les artefacts de diagnostic et les protocoles de gate. Les faits sont séparés de la prose LLM.

### Toolchain Factory

Inclura la vérification stricte, la compilation et l'assemblage. Elle produit l'artefact runtime mais n'est jamais importée par lui. Sa configuration et ses dépendances seront définies après le Stage 0A.

### Produit mesuré

Inclut le code, les builds et les gestionnaires de dépendances Coday/AgentOS. Il est observé et invoqué uniquement par les oracles ou par des intégrations explicitement bornées.

## Autorité et flux

Les sources d'autorité sont inventoriées dans [AUTHORITY_SOURCES.md](AUTHORITY_SOURCES.md). Les dépendances autorisées sont définies dans [DEPENDENCY_MATRIX.md](DEPENDENCY_MATRIX.md). En cas de divergence pendant la coexistence, le module `.mjs` actuellement exécuté reste l'autorité jusqu'à une bascule explicite ; ensuite, la source `.ts` devient l'autorité et le JavaScript généré devient un produit de build.

## Migration incrémentale

La coexistence `.mjs`/`.ts` est temporaire et doit rester lisible. Aucun chargeur TypeScript runtime n'est admis. Le premier candidat est `lib/active-case.mjs`, choisi pour valider le mécanisme avec une surface réduite. La migration doit préserver ses API et invariants observables avant d'étendre la démarche.

Les bascules doivent être réversibles. Le runtime existant reste disponible jusqu'à validation de l'artefact, puis un dernier artefact sain doit pouvoir être restauré indépendamment du workspace produit.

## Décisions encore ouvertes

Le Stage 0B devra décider, avec des critères reproductibles :

- le minimum Node ;
- les outils exacts de vérification, compilation et assemblage ;
- l'emplacement et la publication de l'artefact ;
- la stratégie de sourcemaps ;
- la copie, l'incorporation ou la résolution des assets ;
- les contraintes sur les imports dynamiques et leur fermeture dans l'artefact.

Aucune de ces questions ne doit être résolue implicitement par l'ajout prématuré d'une configuration ou d'un script.

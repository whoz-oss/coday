# Matrice des dépendances de la Factory

## Cluster shutdown opérationnel

| Depuis | Vers | Statut | Contrainte |
|---|---|---|---|
| Application shutdown TS | `CaseTerminator` et callbacks injectés | Autorisé | aucune importation AgentOS/registry/gate |
| Adaptateur HTTP AgentOS | `fetch` | Borné | timeout, un seul essai, aucun retry |
| Adaptateur process | SIGTERM / exit | Borné | composition explicite |
| `run.mjs` | bundle + autorité legacy gate | Autorisé | registry et active-case proviennent directement du bundle; aucune duplication d’état |
| Bundle opérationnel | `agentos.mjs` | Interdit | le bundle ne charge pas le client complet |

## Légende

- **Autorisé** : dépendance conforme à l'architecture.
- **Borné** : autorisé derrière un contrat explicite et avec gestion d'échec.
- **Build uniquement** : absent du runtime livré.
- **Interdit** : compromet l'autonomie ou la frontière de confiance.

## Matrice cible

| Depuis | Vers | Statut | Contraintes |
|---|---|---|---|
| Runtime Factory | APIs natives Node | Autorisé | Seulement APIs couvertes par le minimum Node à décider au Stage 0B. |
| Runtime Factory | Système de fichiers / processus OS | Borné | Accès explicites nécessaires aux mesures, oracles, registres et gates. |
| Runtime Factory | Artefacts et données Factory | Autorisé | Formats et chemins stables ; aucune dépendance à un compilateur. |
| Runtime Factory | AgentOS HTTP | Borné | Via adaptateur ; timeout, erreurs et indisponibilité observables. |
| Runtime Factory | Code interne AgentOS / classes Kotlin | Interdit | La frontière est le contrat réseau. |
| Runtime Factory | `node_modules` | Interdit | L'artefact doit être autonome. |
| Runtime Factory | pnpm ou Nx pour démarrer/charger la Factory | Interdit | Leur santé ne doit pas conditionner l'instrument. |
| Runtime Factory | TypeScript, transpileur, bundler ou chargeur TS | Interdit | Compilation avant livraison uniquement. |
| Runtime Factory | pnpm, Nx ou Gradle comme commande oracle | Borné | Autorisés uniquement comme objets de mesure ; leur échec est un verdict/fait du produit. |
| Toolchain Factory | Sources TypeScript Factory | Autorisé | Typage strict et production de l'artefact ESM. |
| Toolchain Factory | Outils de compilation/assemblage Factory | Build uniquement | Choix exact reporté au Stage 0B. |
| Toolchain Factory | Dépendances du workspace produit | Interdit | Pas de réutilisation de son installation, scripts ou graphe Nx. |
| Toolchain Factory | pnpm/Nx racine du produit | Interdit | La build Factory doit rester isolée même si le produit est cassé. |
| Toolchain Factory | AgentOS en fonctionnement | Interdit | Construire l'artefact ne nécessite aucun case ni service AgentOS. |
| Produit mesuré | Runtime Factory | Interdit | Le produit ne doit pas importer ni piloter internement l'instrument. |
| AgentOS | Runtime Factory | Interdit | Aucun plugin ou import inverse requis. |
| Workflows Factory | Oracles déterministes | Autorisé | Le workflow consomme des faits et codes de sortie. |
| Agents/LLM | Choix ou verdict d'oracle | Interdit | Les agents ne se jugent pas eux-mêmes. |
| Module Factory | Autre module Factory | Autorisé | Import ESM explicite, sans cycle non maîtrisé ; fermeture à traiter au packaging. |
| Artefact ESM | Sources `.ts` ou `.mjs` du checkout au runtime | Interdit | L'artefact livré ne doit pas compléter son code depuis les sources. |
| Entrypoint opérationnel | `src/lib/active-case.ts` | Build uniquement | Import NodeNext fermé dans l'unique bundle monofichier. |
| Bundle opérationnel versionné | Imports `node:*` | Autorisé | Seuls imports runtime externes ; aucune résolution source/toolchain/node_modules ; active-case et registry inclus exactement une fois. |
| `lib/registry.mjs` | Bundle opérationnel | Autorisé | Façade ESM de réexport strictement sans état. |

## Autonomie runtime

Un artefact conforme doit pouvoir être copié avec ses seuls assets runtime explicitement inclus, puis lancé avec Node. La machine d'exécution n'a besoin ni du dépôt source complet ni d'une installation de dépendances JavaScript. L'accès au checkout mesuré reste naturellement nécessaire lorsqu'un workflow doit le lire ou y lancer un oracle.

« Autonome » ne signifie pas que chaque workflow réussit sans services ou outils externes : un workflow AgentOS exige le réseau et le service ; un oracle Nx exige que le produit offre la commande mesurée. Cela signifie que ces absences sont détectées **après le démarrage de la Factory** comme des faits opérationnels, et non comme une impossibilité de charger son propre code.

## Build isolé

La toolchain Factory devra avoir son propre graphe de dépendances et son propre point d'entrée de build. Elle ne devra pas être enregistrée comme projet Nx du produit ni invoquer un script racine comme condition intrinsèque. Les mécanismes exacts d'installation et de build restent à décider au Stage 0B ; cette matrice contraint leur résultat, sans prescrire prématurément un outil.

## Imports, assets et sourcemaps

Les imports statiques internes doivent être fermés dans l'artefact ou résolus vers des fichiers livrés avec lui. La politique des imports dynamiques, des assets et des sourcemaps est ouverte jusqu'au Stage 0B. Quelle que soit la décision, elle ne pourra pas introduire de résolution vers `node_modules`, vers les sources du workspace produit ou vers un compilateur au runtime.

## Coexistence et rollback

Le cluster opérationnel charge `run.mjs` et `lib/agentos.mjs` sur le même registre du bundle généré. Depuis le Stage 4A, aucun prototype active-case séparé ne subsiste. L'autonomie complète est différée : `_currentRun` et les gates restent dans leurs modules legacy et sont injectés, afin de préserver l'unicité réelle de ces états.

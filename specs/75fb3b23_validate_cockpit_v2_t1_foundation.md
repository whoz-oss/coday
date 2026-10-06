# Plan: Validation du Socle T1 (Cockpit V2)

## Objectif
Valider et vérifier le socle T1 déjà présent dans le working tree sans recréer les fichiers ni committer de modifications, puis rapporter les résultats bruts.

## État des lieux (Working Tree)
Le socle T1 comprend :
- `apps/cockpit-v2`: Application Angular frontend v2.
- `factory-service`: Service Kotlin Spring Boot hébergeant la configuration et la gestion des ressources statiques pour Cockpit V2 (`CockpitV2Properties`, `CockpitV2WebConfig`, `CockpitV2Assets`, `CockpitV2Controller`, et le test d'intégration `CockpitV2StaticServingIntegrationTest`).

## Étapes de Validation & Exécution

### 1. Build de `cockpit-v2`
- **Commande** : `pnpm nx build cockpit-v2`
- **Attendu** : Compilation Angular / Nx réussie (statut vert).
- **Consigne de correction si échec** : Corriger le code source TS/SCSS/Config dans `apps/cockpit-v2/` jusqu'à obtention d'une compilation sans erreur. Ne pas toucher à `apps/client` ni à `apps/server`.

### 2. Tests d'intégration Kotlin (`factory-service`)
- **Commande** : `./gradlew :factory-service:test --tests "*CockpitV2StaticServingIntegrationTest*"` (depuis la racine) ou `./gradlew test --tests "*CockpitV2StaticServingIntegrationTest*"` depuis `factory-service/`.
- **Attendu** : Execution de la suite de tests Spring Boot pour le service static serving de Cockpit V2, avec tous les tests passants (statut vert).
- **Consigne de correction si échec** : Corriger si nécessaire les classes Kotlin CockpitV2* (`CockpitV2Assets.kt`, `CockpitV2Controller.kt`, `CockpitV2WebConfig.kt`, `CockpitV2Properties.kt`) jusqu'à ce que le test passe.

### 3. Contrôle des contraintes
- Aucune modification autorisée sur `apps/client`, `apps/server` (doit rester exempt de cockpit-v2), `factory/dashboard`.
- Preserver le comportement de `/cockpit`.
- Ne rien committer (`git commit` interdit).

## Verification Finale
Générer un rapport final concis indiquant :
- Statut de chaque commande (VERT / ROUGE).
- Détail des éventuelles corrections apportées.

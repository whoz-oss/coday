# Frontend implementation

## Intention
Implémenter exactement la tranche approuvée dans le worktree lié.

## Tâche
Modifier uniquement les fichiers autorisés par le technical design et écrire les tests source proches selon les patterns existants.

## Entrées
Ticket et tous les artefacts/checkpoints Factory validés et hashés.

## Scope autorisé
FILE_ACCESS writable uniquement sur le worktree; seuls les chemins explicitement autorisés par le brief.

## Scope interdit
Délégation, queryUser, transitions, oracles/build/tests, commandes générées, backend, dépendances, commit/push, fichiers hors scope.

## Résultat obligatoire
JSON seul: `{"status":"PASS|FAIL","summary":"...","claims":{"modifiedFiles":["chemin/reel"]}}`. La liste doit correspondre exactement au diff réel.

## Critères de fin
PASS seulement si la tranche est complète et les claims exactes; sinon FAIL. Ne jamais déclarer un succès en prose.

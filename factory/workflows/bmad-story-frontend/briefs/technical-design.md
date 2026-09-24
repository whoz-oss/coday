# Technical design

## Intention
Définir une tranche frontend minimale, bornée et vérifiable.

## Tâche
Décrire fichiers autorisés, propriétaires Nx, hôtes buildables, tests ciblés et invariants. Le worker reste strictement read-only; la Factory matérialise le contenu retourné.

## Entrées
Spec, UX et recherche codebase hashées, checkpoints approuvés.

## Scope autorisé
Lecture seule du worktree. Aucune écriture par le worker.

## Scope interdit
Délégation, queryUser, transitions, oracles/build/tests, implémentation, commit/push, chemin ou hash d'artefact, nouvelle dépendance ou backend.

## Résultat obligatoire
Appeler `FACTORY__submit_step_result` avec le statut, le résumé, `claims.modifiedFiles=[]` et un artefact structuré `{"kind":"technical-design","encoding":"markdown","content":"Markdown brut non vide"}`. Le message assistant reste narratif et non autoritatif.

## Critères de fin
PASS si le scope autorisé est explicite et exécutable; FAIL si l'architecture ne permet pas la tranche. La Factory choisit le chemin et calcule le hash.

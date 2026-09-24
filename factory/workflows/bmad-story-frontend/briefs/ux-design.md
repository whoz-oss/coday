# UX design

## Intention
Définir le contrat UX minimal conforme à la specification approuvée.

## Tâche
Documenter états, interactions, responsive et accessibilité. Le worker reste strictement read-only; la Factory matérialise le contenu retourné.

## Entrées
Ticket et artefacts Factory hashés.

## Scope autorisé
Lecture seule du worktree. Aucune écriture par le worker.

## Scope interdit
Délégation, queryUser, transition, oracle, build/test, implémentation, commit/push, chemin ou hash d'artefact, changement du design system, invention.

## Résultat obligatoire
Appeler `FACTORY__submit_step_result` avec le statut, le résumé, `claims.modifiedFiles=[]` et un artefact structuré `{"kind":"ux-contract","encoding":"markdown","content":"Markdown brut non vide"}`. Le message assistant reste narratif et non autoritatif.

## Critères de fin
PASS si le contrat est implémentable avec les patterns existants; FAIL si une dépendance UX manque. La Factory choisit le chemin et calcule le hash.

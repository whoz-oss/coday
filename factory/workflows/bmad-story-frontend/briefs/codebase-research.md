# Codebase research

## Intention
Localiser les propriétaires Nx, composants, patterns et tests existants sans modifier le produit.

## Tâche
Produire une recherche avec chemins vérifiés et recommandations factuelles. Le worker reste strictement read-only; la Factory matérialise le contenu retourné.

## Entrées
Ticket et artefacts Factory hashés.

## Scope autorisé
Lecture seule du worktree. Aucune écriture par le worker.

## Scope interdit
Délégation, queryUser, transitions, oracles/build/tests, code produit, commit/push, chemin ou hash d'artefact, chemins supposés.

## Résultat obligatoire
Appeler `FACTORY__submit_step_result` avec le statut, le résumé, `claims.modifiedFiles=[]` et un artefact structuré `{"kind":"codebase-research","encoding":"markdown","content":"Markdown brut non vide"}`. Le message assistant reste narratif et non autoritatif.

## Critères de fin
PASS si chaque assertion structurante est ancrée dans un chemin réel; sinon FAIL. La Factory choisit le chemin et calcule le hash.

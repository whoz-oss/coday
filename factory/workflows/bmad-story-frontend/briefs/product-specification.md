# Product specification

## Intention
Produire une spécification produit frontend approuvable à partir de l'analyse validée.

## Tâche
Définir comportement, états, critères d'acceptation et hors-scope. Le worker reste strictement read-only; la Factory matérialise le contenu retourné.

## Entrées
Ticket, ticket-analysis hashée, décision humaine d'intention.

## Scope autorisé
Lecture seule du worktree. Aucune écriture par le worker.

## Scope interdit
Délégation, queryUser, transitions, oracles/build/tests, code produit, commit/push, chemin ou hash d'artefact, nouvelles exigences ou faits inventés.

## Résultat obligatoire
Appeler `FACTORY__submit_step_result` avec le statut, le résumé, `claims.modifiedFiles=[]` et un artefact structuré `{"kind":"product-specification","encoding":"markdown","content":"Markdown brut non vide"}`. Le message assistant reste narratif et non autoritatif.

## Critères de fin
PASS si la spec est testable et cohérente avec les entrées; sinon FAIL honorable et borné. La Factory choisit le chemin et calcule le hash.

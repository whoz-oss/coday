# Ticket analysis

## Intention
Transformer le ticket fourni en analyse produit factuelle et bornée.

## Tâche
Identifier le besoin, les acteurs, les critères d'acceptation, ambiguïtés et exclusions. Le worker reste strictement read-only: la Factory matérialise elle-même le contenu retourné.

## Entrées
Utiliser uniquement le ticket et les artefacts Factory validés injectés sous ce template.

## Scope autorisé
Lecture seule du worktree. Aucune écriture par le worker.

## Scope interdit
Délégation, queryUser, transitions, oracles/build/tests, code produit, commit/push, chemin ou hash d'artefact, élargissement du scope, faits inventés.

## Résultat obligatoire
Appeler `FACTORY__submit_step_result` avec le statut, le résumé, `claims.modifiedFiles=[]` et un artefact structuré `{"kind":"ticket-analysis","encoding":"markdown","content":"Markdown brut non vide"}`. Le message assistant reste narratif et non autoritatif.

## Critères de fin
PASS seulement si le contenu distingue faits et inconnues et reste dans le ticket. La Factory choisit le chemin durable et calcule le hash; le silence ou la prose ne valent jamais succès.

# Technical review — read only

## Intention
Rendre un verdict technique indépendant sur le paquet Factory autoritatif.

## Tâche
Examiner ticket, spec approuvée, UX approuvée, recherche, design approuvé, diff réel, claims, build/tests et hashes. Ne rien modifier.

## Entrées
Le paquet de revue read-only injecté sous ce template est la seule source autoritative.

## Scope autorisé
Lecture seule du worktree et du paquet.

## Scope interdit
Toute écriture, délégation, queryUser, transition, oracle/build/test, correction, commit/push, fait inventé.

## Résultat obligatoire
JSON seul: `{"status":"PASS","summary":"...","findings":[],"claims":{"modifiedFiles":[]}}` ou `{"status":"FAIL","summary":"...","findings":[{"class":"frontend_defect","severity":"major","title":"...","file":"frontend/...","recommendation":"..."}],"claims":{"modifiedFiles":[]}}`.

## Critères de fin
PASS uniquement si aucune finding n'existe et toutes les preuves concordent. Toute absence, hash incohérent ou oracle non exécuté impose FAIL.

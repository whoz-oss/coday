# Validation humaine des checkpoints dans le cockpit Factory

## Résumé
Le cockpit Factory peut maintenant traiter un checkpoint humain depuis le panneau de détail d’une étape. Lorsqu’un step de la frise est humain (`phaseKind`, `lane` ou `responsibility.kind` à `human`) et attend une décision, le panneau charge l’interaction ouverte associée au `stepId` et affiche son nom, son prompt éventuel, un commentaire facultatif limité à 2000 caractères, ainsi que les actions **Approuver** et **Rejeter**.

Le bouton envoie uniquement le contrat strict de réponse : `expectedRevision`, `actionId` (`approve` ou `reject`) et, si renseigné, `text`. Les boutons sont désactivés pendant l’envoi. Une réponse réussie affiche un retour, relance le workflow, puis recharge projection, timing et frise. Un conflit de révision recharge l’interaction afin de réafficher les boutons avec la révision à jour. Si aucune interaction `waiting` n’est trouvée, l’état d’attente est affiché sans action de décision.

## Fichiers concernés

- `factory/dashboard/js/components/phase-panel.mjs`
  - Ajoute la détection des steps humains et le filtrage de l’interaction `waiting` correspondant au step.
  - Étend `loadPhaseEnrichment` pour appeler `GET /api/factory/workflows/{workflowId}/interactions?namespaceId=...` lors de la sélection d’un step humain/en attente.
  - Rend la carte de validation dans le panneau, avec prompt, textarea, boutons et feedback d’erreur/succès.
- `factory/dashboard/js/views/run-detail.mjs`
  - Passe `workflowId` et `namespaceId` au chargement d’enrichissement.
  - Intercepte les clics sur `data-checkpoint-action`, construit le body strict et appelle `POST /api/factory/workflows/{workflowId}/interactions/{interactionId}/reply`.
  - Envoie l’acteur cockpit (`local-dev-user` par défaut) et le namespace via les options de l’ApiClient, puis recharge l’état après réponse.
  - Exporte aussi `buildReplyBody` et `DEFAULT_ACTOR_ID`.
- `factory/dashboard/js/components/phase-panel.test.mjs`
  - Couvre la classification humaine, le rendu avec/sans interaction ouverte, le prompt/commentaire, la longueur maximale, les étapes résolues et le chargement des interactions.
- `factory/dashboard/js/views/run-detail.test.mjs`
  - Vérifie le body `approve`/`reject`, `expectedRevision`, le commentaire tronqué, les paramètres namespace/acteur, l’appel de reprise et le rechargement après conflit 409.
- `specs/f4a94778_human_checkpoint_cockpit_validation.md`
  - Consigne le contrat UI/API, la vérification de reprise et la solution retenue.

## Reprise du DAG
La vérification consignée dans la spec montre que `reply` résout durablement l’interaction mais ne relance pas seul la boucle `SessionRunService.runSession`. Le cockpit appelle donc ensuite `POST /api/factory/workflows/{workflowId}/continue` avec `{ namespaceId }`. Cet appel est best-effort : il s’appuie sur le `factory.session.default-repo-root` côté serveur lorsque le `repoRoot` n’est pas fourni. Si cette configuration manque, la réponse au checkpoint reste acquise et le cockpit affiche que la reprise automatique est impossible.

## Vérification
Tests vanilla ESM, sans build ni dépendance :

```bash
node --test factory/dashboard/js/components/phase-panel.test.mjs
node --test factory/dashboard/js/views/run-detail.test.mjs
```

Vérification manuelle : ouvrir un run `waiting_human` dans le cockpit servi par `factory-service`, sélectionner le bloc checkpoint dans la frise, vérifier le prompt/champ commentaire et les boutons **Approuver**/**Rejeter**, puis cliquer sur **Approuver**. Contrôler la réponse API, le retour de succès, l’appel `/continue` et le rafraîchissement de la frise vers l’état résolu. Pour tester le rejet, refaire avec **Rejeter** et vérifier `actionId: "reject"`.

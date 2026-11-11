# Forge plugin — lifecycle notifications

`ForgeBmadLifecycleObserver` conserve les notifications Jira (transitions et commentaires d’audit), les événements lifecycle et leur déduplication.

**La synchronisation du fichier `sprint-status.yaml` est désactivée.** Les valeurs `repo_root` et `sprint_status_path` du `domainState` ne déclenchent aucune lecture, création ou modification de fichier via les callbacks de démarrage/fin de fragment ou de fin de Run. Les fichiers existants sont laissés inchangés. Le champ événementiel `sprintStatusUpdated` est conservé pour compatibilité et vaut toujours `false`.

Le plugin ne dispose pas d’un port cœur fournissant à la fois le workspace autoritatif du Run et une occupation d’exécution D7. Une simple validation de chemin ne remplace pas ces garanties. Aucune nouvelle infrastructure ni accès direct au cœur n’est ajouté pour réactiver cette option.

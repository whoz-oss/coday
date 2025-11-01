# Atelier Coday - De bout en bout (2h)

## 📋 Plan de l'atelier

### Partie 1 : Fondations & Démo (50-60 min)
- **A.** Socle technique (15 min) - avec slides
- **B.** Configuration en pratique (10 min) - live demo
- **C.** Workflow canonique (25-30 min) - démo live sur ticket

### Partie 2 : Pratique par chapitre (60-70 min)
- Organisation par chapitre (Front / Back / autres)
- Application sur tickets réels
- Partage d'expériences

---

## 🎯 Objectifs

1. Aligner les pratiques des devs sur l'usage de Coday
2. Partager les bonnes pratiques et usages avancés
3. Pratiquer le workflow complet : ticket → PR → merge
4. Comprendre comment tirer le meilleur parti des agents

---

# 📊 SLIDES

---

## Slide 1 : Modalités d'interaction

### Comment interagir avec Coday ?

#### 💬 Chat textuel
- Terminal (CLI)
- Interface web

#### 🎤 Capture vocale
- Reconnaissance vocale intégrée
- Idéal pour décrire des problèmes complexes
- Gain de temps sur la saisie

#### 🖼️ Images
- Partage de screenshots
- Diagrammes, maquettes
- Messages d'erreur visuels
- **Cas d'usage réel** : "Fix de bugs design en donnant une image annotée"

> 💡 **Astuce** : Combiner plusieurs modalités pour plus d'efficacité

---

## Slide 2 : Les niveaux de configuration

### Comment s'organisent les configurations ?

```
┌─────────────────────────────────────────────────┐
│              CODAY (Framework)                  │
│                                                 │
│  • Agents techniques internes                   │
│  • Capacités système de base                    │
│  • Non configurable par l'utilisateur           │
│                                                 │
└─────────────────────────────────────────────────┘
                      ↓ configure
┌─────────────────────────────────────────────────┐
│                  PROJECT                        │
│                                                 │
│  • Agents projet disponibles (@agent-name)      │
│  • Teams (#team-name)                           │
│  • Tools & intégrations                         │
│  • Mémoire projet (partagée par tous)           │
│  • Configuration commune à tous les users       │
│                                                 │
└─────────────────────────────────────────────────┘
                      ↓ personnalise
┌─────────────────────────────────────────────────┐
│                    USER                         │
│                                                 │
│  • Préférences personnelles                     │
│  • Credentials (API keys, tokens)               │
│  • Mémoire utilisateur (privée)                 │
│  • Intégrations user-specific                   │
│  • Ne pollue pas les autres utilisateurs        │
│                                                 │
└─────────────────────────────────────────────────┘
```

### 🔑 Points clés

- **User** : ce qui vous est propre (credentials, préférences)
- **Project** : ce qui est partagé par toute l'équipe (agents, tools)
- **Coday** : le framework, vous ne le touchez pas

⚠️ **Difficulté remontée** : "La config de coday reste bien galère" → On va clarifier ça ensemble !

---

## Slide 3 : Les deux types d'agents

### Agents Projet

```
┌──────────────────────────────────────────────────────┐
│              AGENTS PROJET                           │
│                                                      │
│  ✅ Adressables via @agent-name                      │
│  ✅ Configurés dans le projet                        │
│  ✅ Expertise domaine spécifique                     │
│  ✅ Peuvent faire partie de Teams (#team-name)       │
│                                                      │
│  📌 Exemples :                                       │
│     @sway    - Software agent                        │
│     @archay  - Architecture expert                   │
│     @octopuss - GitHub specialist                    │
│     @pm      - Product manager                       │
│                                                      │
└──────────────────────────────────────────────────────┘
```

### Agents Techniques

```
┌──────────────────────────────────────────────────────┐
│            AGENTS TECHNIQUES (internes)              │
│                                                      │
│  ❌ Non adressables directement                      │
│  ⚙️  Internes au système Coday                       │
│                                                      │
│  🔧 Opérations spécialisées :                        │
│     • Curation de la mémoire                         │
│     • Résumés de contexte                            │
│     • Supervision de teams                           │
│     • Médiation inter-agents                         │
│                                                      │
│  💡 Travaillent en coulisses pour vous               │
│                                                      │
└──────────────────────────────────────────────────────┘
```

---

## Slide 4 : Workflow canonique

### Du ticket Jira à la PR mergée

```
🎫 JIRA                    💻 CODAY                    🔄 GITHUB
   │                          │                           │
   │ Ticket → In Progress     │                           │
   ├─────────────────────────→│                           │
   │                          │                           │
   │                          │ 1️⃣ ANALYSE               │
   │                          │   • Quel agent ?          │
   │                          │   • Quels outils ?        │
   │                          │   • Quelle approche ?     │
   │                          │                           │
   │                          │ 2️⃣ IMPLÉMENTATION        │
   │                          │   • Prompts progressifs   │
   │                          │   • Rôle de garde-rail    │
   │                          │   • Corrections itératives│
   │                          │                           │
   │                          │ 3️⃣ GÉNÉRATION PR         │
   │                          ├──────────────────────────→│
   │                          │                           │
   │ 4️⃣ FEEDBACK              │                           │ PR créée
   │←─────────────────────────┤                           │
   │  • Ce qui est fait       │                           │
   │  • Ce qui reste à faire  │                           │
   │  • Impact pour les tests │                           │
   │                          │                           │
   │                          │                           │ Merge
   │ Ticket → Done            │                           │
   │←──────────────────────────────────────────────────────┤
```

### 🎯 Trunk-based development

- Petites PR successives
- Feedback régulier dans Jira
- Itérations rapides

💬 **Retour terrain** : "Il m'a par contre généré pas mal de code inapproprié dont certains bouts que j'avais zappés mais qu'il a vu en PR... il se corrige lui-même donc c'est cool :-)"

---

## Slide 5 : Les 3 piliers des bonnes pratiques

### 1️⃣ INVERSER LES QUESTIONS

```
❌ Mauvais : "Fais-moi X"
❌ Mauvais : "Implémente cette feature"

✅ Bon : "Quels agents peuvent m'aider sur X ?"
✅ Bon : "Quels outils sont disponibles pour Y ?"
✅ Bon : "Comment devrais-je aborder ce problème ?"
✅ Bon : "Analyse d'abord le code existant avant de proposer"
```

**Pourquoi ?** Coday connaît mieux ses capacités que vous. Laissez-le vous guider.

---

### 2️⃣ ÊTRE LE GARDE-RAIL (critique !)

```
❌ Mauvais : Laisser Coday partir dans les buissons
❌ Mauvais : Accepter n'importe quelle suggestion
❌ Mauvais : Faire confiance aveuglément

✅ Bon : Utiliser le vocabulaire technique approprié
✅ Bon : Orienter progressivement avec des prompts clairs
✅ Bon : Corriger les dérives rapidement
✅ Bon : Valider chaque étape avant de continuer
✅ Bon : TOUJOURS relire et comprendre le code généré
```

**Pourquoi ?** L'IA a besoin de contexte et de direction. Vous êtes le pilote.

⚠️ **Retour critique** : "Il ne faut pas s'y fier car il a beaucoup d'aplomb dans ses réponses même quand il invente. Sur 3 preuves que je lui ai demandé pour justifier ses dires, les 3 étaient inventées !"

🎯 **L'IA est un outil, pas un oracle** : restez critiques, vérifiez, challengez.

---

### 3️⃣ ITÉRATION > BIG BANG

```
❌ Mauvais : "Implémente toute la feature d'un coup"
❌ Mauvais : Une seule grosse PR avec tout

✅ Bon : Trunk-based - petites PR successives
✅ Bon : Feedback Jira après chaque PR
✅ Bon : Prompts par étapes logiques
✅ Bon : Tester et valider à chaque itération
✅ Bon : Découper en sous-tâches claires
```

**Pourquoi ?** Plus facile à reviewer, débugger, et ajuster en cours de route.

💬 **Retour terrain** : "Dev par step une feature" = approche gagnante

---

## Slide 6 : Cas d'usage réels qui fonctionnent

### ✅ Ce qui marche bien

#### 🧪 Génération de tests
- "Quasi tous les jeux de données dans les tests sont générés par Coday"
- Tests unitaires et E2E
- Datasets de test

#### 🔄 Migrations et refactoring en masse
- "Grosse utilité sur des migrations en masse touchant plus de 1000 fichiers"
- Refacto des filtres, migrations de patterns

#### 📖 Analyse et compréhension de code
- "Pour comprendre le flux de données sur les APIs"
- "Pour m'orienter dans des parties du code que je ne connais pas"
- Vision d'ensemble sur du code complexe

#### 🐛 Debug et investigation
- "Analyse bug front, proposition de refactor/optimisation"
- Analyse d'erreurs non évidentes

#### 📝 Documentation
- Création d'ADR
- Rédaction de documentation de migration
- "Analyse de la migration grails pour tirer des chiffres et générer un squelette d'article"

---

## Slide 7 : Pièges fréquents et comment les éviter

### 🚨 Problèmes identifiés

#### 1. Coday part "dans les buissons"
**Symptômes** : 
- Génère du code inapproprié
- Part sur une mauvaise piste
- Solutions trop complexes / over-engineering

**Solutions** :
- ✅ Découper en étapes plus petites
- ✅ Valider chaque étape avant de continuer
- ✅ Utiliser des prompts plus directifs et précis
- ✅ Ne pas hésiter à stopper et recadrer

#### 2. Perte de contexte / crash
**Symptômes** :
- "Perte de la conversation en cas de crash"
- Thread difficile à reprendre
- Compactage qui casse le contexte

**Solutions** :
- ✅ Ouvrir la conversation dans VSCode en parallèle
- ✅ Sauvegarder les points importants dans la mémoire
- ✅ Reprendre avec un résumé clair du contexte

#### 3. Lenteur / problèmes de connexion
**Symptômes** :
- Temps de réponse long
- Déconnexions fréquentes
- "Coday qui se coupe"

**Solutions** :
- ✅ Travailler sur des tâches plus petites
- ✅ Utiliser des prompts concis
- ⚠️ Problème connu, améliorations en cours

#### 4. Confiance excessive
**Symptômes** :
- "Il a beaucoup d'aplomb même quand il invente"
- Citations de doc inventées
- Code qui compile mais n'est pas optimal

**Solutions** :
- ✅ **TOUJOURS** vérifier le code généré
- ✅ Tester systématiquement
- ✅ Challenger les réponses
- ✅ Demander des sources vérifiables

---

## Slide 8 : Spécificités Front vs Back

### 🎨 Front

#### Points d'attention
- CSS/HTML plus compliqué pour l'IA
- Composants Angular : vocabulaire précis nécessaire
- "Quand j'essaie de passer des tickets front c'est impossible" (si mal utilisé)

#### Bonnes pratiques
- ✅ Utiliser les agents front spécialisés
- ✅ Fournir des images annotées pour les bugs design
- ✅ Être très précis sur le vocabulaire (controller, service, component)
- ✅ Valider rapidement le rendu visuel
- ✅ Itérer sur les styles

#### Cas d'usage qui marchent
- Implémentation de composants (ex: NX executor, création de store)
- Tests unitaires front
- Opérateurs RxJS
- Migration de fichiers en masse selon un pattern

### ⚙️ Back

#### Points forts
- Architecture plus prévisible
- Patterns bien établis
- Meilleure compréhension par l'IA

#### Bonnes pratiques
- ✅ Expliciter les patterns (DDD, hexagonal, etc.)
- ✅ Demander les tests en même temps
- ✅ Décomposer les migrations (lecture → transformation → écriture)

#### Attention
- Tendance au mock excessif dans les tests
- Peut ne pas faire de data-driven pour simplifier

---

## Slide 9 : Exemples de bons prompts

### ❌ Prompts vagues

```
"Fixe ce bug"
"Fais le ticket"
"Améliore la performance"
"Crée un service"
```

### ✅ Prompts structurés

```
"Analyse le ticket JIRA-123. 
Quels agents sont les plus adaptés pour traiter ce problème ? 
Propose-moi une approche par étapes."

"Je veux créer un service pour gérer les notifications.
Montre-moi d'abord la structure de fichiers existante,
puis propose-moi l'architecture en suivant les patterns du projet."

"Cette fonction a un problème de performance.
Analyse d'abord le code actuel, identifie les bottlenecks,
puis propose des optimisations une par une."

"Voici une image annotée du bug visuel [image].
Identifie les composants concernés puis propose un fix."
```

### 🎯 Structure idéale

1. **Contexte** : Où es-tu, que veux-tu faire ?
2. **Question** : Demande de l'aide pour la stratégie
3. **Étapes** : Décompose en actions séquentielles
4. **Validation** : Demande confirmation avant exécution

---

## Slide 10 : Usages avancés

### 📚 Bibliothèque de prompts (Thomas Martin)
- Prompts pré-câblés pour des tâches récurrentes
- Réutilisables et partageables
- Gain de temps énorme
- **À voir en démo**

### 🏗️ Agents temporaires (Léo)
- Création d'agents éphémères pour un chantier spécifique
- Configuration ad-hoc
- Suppression après usage
- **À voir en démo**

### 💾 Hack de la mémoire (Émilie)
- Optimisation du système de mémoire
- Contexte persistant intelligent
- **À voir en démo**

### 🎫 Création de tickets Jira
- Générer des tickets en quelques secondes
- Partir de notes brutes ou de discussions Slack
- Structuration automatique
- 💬 "Any task to open Jira issue based on a discussion in Slack"

### 🔍 PR Review
- Analyse automatique des PR
- Détection de problèmes
- "En review il peut trouver des détails intéressants"

---

## Slide 11 : Gains de productivité réels

### 📊 Données de l'enquête

**Temps gagné par semaine** :
- 🟢 **37%** : 1 journée ou plus
- 🟢 **31%** : 4-8 heures
- 🟡 **25%** : 2-4 heures
- 🔵 **6%** : 1-2 heures

**Impact perçu** :
- 🚀 **44%** : Impact très positif (+++)
- ✅ **44%** : Impact positif (++)
- 🤔 **12%** : Impact légèrement positif (+)

**Qualité du code** :
- 📈 **75%** : Amélioration significative
- 📊 **19%** : Légère amélioration
- ➖ **6%** : Aucun impact

### 💡 Le consensus

> "Une nette amélioration de la qualité de code et de la force de frappe des équipes de dev chez Whoz. On en a encore sous le pied je pense ce n'est que le début."

---

## Slide 12 : Les vraies limites à connaître

### 🎯 Soyons honnêtes

#### Ce que Coday ne fait PAS (encore) bien

1. **Contexte métier complexe**
   - "Je ne fais pas confiance à l'IA pour la description du contexte métier de Whoz"
   - Solution : Lui fournir explicitement le contexte

2. **Problèmes pointus (niveau senior)**
   - "En tant que senior il ne m'aide pas souvent sur des problèmes pointus"
   - L'IA excelle sur le boilerplate et l'exploration

3. **Tickets mal définis**
   - "Aujourd'hui il est encore trop difficile pour coday si le ticket n'est pas forcément bien décrit"
   - Solution : Grooming plus rigoureux

4. **Maintenir la cohérence**
   - Tendance à l'over-engineering
   - Non homogénéité des solutions
   - "Tendance à créer de nouvelles choses plutôt que réutiliser"

### ⚖️ L'équilibre à trouver

- ✅ Excellent pour : boilerplate, tests, exploration, migrations
- ⚠️ Vigilance sur : architecture, cohérence, contexte métier
- ❌ Ne remplace pas : la réflexion, la compréhension profonde, le jugement

---

## Slide 13 : Organisation de la pratique

### 📍 Par chapitre

**Chapitre Front**
- Benjamin, Léo, Valdes, Émilie
- Focus : composants Angular, agents front, CSS/HTML

**Chapitre Back**
- Reste de l'équipe dev
- Focus : architecture, services, API, migrations

**Autres chapitres**
- Selon composition et besoins

### 🎯 Objectif de la session pratique

1. **Prendre un ticket réel** (petit, borné)
2. **Appliquer le workflow complet** 
   - Analyse → Implémentation → PR → Feedback Jira
3. **Partager les difficultés** rencontrées
4. **Échanger les astuces** entre membres
5. **Pratiquer les 3 piliers** (inverser questions, garde-rail, itération)

### ⏱️ Format : 60-70 minutes

- Chaque chapitre travaille sur un ticket adapté à son domaine
- Vincent A. tourne entre les groupes pour support
- On se retrouve en fin pour débrief (10 min)

---

## Slide 14 : Support & Suivi

### 💬 Pendant l'atelier
- Channel Slack dédié pour questions
- Vincent A. disponible pour tourner entre chapitres
- Partage de découvertes en temps réel

### 📅 Après l'atelier

**Follow-up par chapitre**
- Sessions à organiser par vous-mêmes
- Vincent A. peut participer si disponible
- Approfondissement des sujets spécifiques

**Atelier 3D à venir**
- Design + Dev + autres cercles
- Usage transverse de Coday
- Date à définir

**Améliorations continues**
- Vos retours feront évoluer Coday
- "Nous ne faisons que très peu de contribution à Coday ce qui rend son évolution lente"
- 💡 Proposition : 1 jour/mois/dev pour améliorer Coday ?

### 📚 Ressources
- Documentation projet : `./doc/`
- Configuration : `PROJECT_CONFIGURATION.md`
- Ce support : `doc/atelier-coday-2h.md`

---

## Slide 15 : Les bonnes nouvelles à venir

### 🚀 Améliorations demandées en cours

- ⏮️ Revenir au message précédent (annoncé en démo 3D)
- 💾 Auto-save de summary pour reprendre après crash
- 🔌 Plus d'intégrations IDE (IntelliJ plugin demandé)
- 🤖 Review automatique des PR GitHub
- 🔍 Recherche sémantique dans le code
- 📊 Intégration avec les logs et la supervision
- 🎯 Slack connector pour workflow async

### 💡 Votre contribution compte

- Vos retours font évoluer le produit
- Partagez vos cas d'usage
- Proposez des améliorations
- Contribuez au code si possible

---

## Slide 16 : Messages clés à retenir

### 🎯 Les 5 commandements

1. **Inverser tu questionneras**
   - "Coday, comment m'aider ?" > "Coday, fais ça"

2. **Garde-rail tu seras**
   - Vérifier, challenger, corriger
   - Ne JAMAIS faire confiance aveuglément

3. **Par étapes tu avanceras**
   - Itération > big bang
   - Petites PR, feedback continu

4. **Critique tu resteras**
   - L'IA est un outil, pas un oracle
   - Comprendre > copier-coller

5. **Expérience tu partageras**
   - Ce qui marche, ce qui marche pas
   - Apprendre ensemble

### 💬 Citation finale

> "Un vrai interlocuteur, permettant d'avoir une autre vision, de confronter ses idées et d'avancer de manière itérative (même si parfois l'IA ne part pas dans la bonne direction). Permet de gagner du temps (mais attention, toujours rester critique sur les propositions)."

---

## Questions avant la démo ?

---

# 🎬 FIN DES SLIDES

## Notes pour la démo live (30 min)

### Setup (2 min)
- Ouvrir VSCode + Coday web
- Montrer la conversation dans les deux
- Expliquer pourquoi (backup en cas de crash)

### Ticket de démonstration (25 min)
- **Type** : Dette technique back (ou front selon guest)
- **Exemple** : Migration converteur → mappeur / abstruct
- **Déroulé** :
  1. **Analyse** (5 min)
     - "Coday, quels agents peuvent m'aider sur ce ticket ?"
     - Discussion sur l'approche
     - Découpage en étapes
  
  2. **Implémentation étape 1** (8 min)
     - Prompt structuré
     - Montrer le code généré
     - **IMPORTANT** : Relire et challenger
     - Corriger une dérive (préparée à l'avance)
  
  3. **Tests** (5 min)
     - Génération des tests
     - Montrer les limites (mocks excessifs)
     - Ajustement
  
  4. **PR et Feedback Jira** (5 min)
     - Génération de la PR
     - Rédaction du feedback pour Jira
     - Ce qui est fait / reste à faire
  
  5. **Cas "buissons"** (2 min)
     - Montrer un cas où ça part en vrille
     - Comment recadrer rapidement

### Points à montrer absolument
- ✅ Fichiers de config (User/Project)
- ✅ Sélection d'agent approprié
- ✅ Prompts progressifs vs big bang
- ✅ Relecture critique du code
- ✅ Correction de dérive
- ✅ Validation à chaque étape

### Guests à faire intervenir (total 15-20 min)

#### Thomas Martin (5 min) - Bibliothèque de prompts
- Comment il structure ses prompts réutilisables
- Exemple concret sur un cas back
- Où les stocker / comment les partager

#### Léo (10 min) - Agents temporaires + Front
- Création d'un agent temporaire pour un chantier
- Demo front : composant Angular
- Techniques pour éviter les pièges CSS/HTML

#### Émilie (3 min) - Hack mémoire
- Comment elle optimise le système de mémoire
- Gains concrets

---

## Checklist pré-atelier

### Préparation technique
- [ ] Environnement de démo prêt (VSCode + Coday web)
- [ ] Ticket de démo choisi et analysé
- [ ] Cas "buissons" préparé (dérive contrôlée)
- [ ] Config files à montrer identifiés
- [ ] Exemples de bons/mauvais prompts prêts

### Coordination
- [ ] Guests briefés (timing, contenu précis)
  - [ ] Thomas Martin : prompts (5 min)
  - [ ] Léo : agents temp + front (10 min)
  - [ ] Émilie : mémoire (3 min)
- [ ] Channel Slack créé et communiqué
- [ ] Message dans #dev pour rediriger questions
- [ ] Invitations envoyées (Thomas San Andres optionnel)

### Matériel
- [ ] Slides converties en format présentable
- [ ] Tickets pour la pratique identifiés (1 front, 1 back minimum)
- [ ] Documentation de référence accessible
- [ ] Backup de la démo (si crash)

### Communication
- [ ] Rappel 2 jours avant
- [ ] Rappel du channel Slack pour questions pré-atelier
- [ ] Objectifs clairs communiqués
- [ ] Format et timing explicites

---

## Script d'introduction (5 min)

Bonjour à tous ! 👋

Merci d'être là pour cet atelier sur Coday. On va passer 2h ensemble pour :
1. Aligner nos pratiques
2. Partager ce qui marche (et ce qui marche pas)
3. Pratiquer ensemble

**Pourquoi cet atelier ?**
Vous avez été 75% à demander des sessions de partage d'expérience. Vous avez parlé de gains de temps énormes (jusqu'à 1 journée/semaine), mais aussi de difficultés réelles :
- Coday qui part dans les buissons
- Perte de contexte
- Confiance excessive dans les réponses

**Objectif** : qu'on ressorte tous avec des pratiques plus solides et une utilisation plus efficace.

**Format** :
- 1h de partage (avec démos et guests)
- 1h de pratique par chapitre
- Safe space : on partage nos galères aussi !

**Message important** : Coday est un outil puissant mais il faut rester critique. On va voir ensemble comment en tirer le meilleur.

C'est parti ! 🚀

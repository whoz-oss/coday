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

---

## Slide 5 : Les 3 piliers des bonnes pratiques

### 1️⃣ INVERSER LES QUESTIONS

```
❌ Mauvais : "Fais-moi X"
❌ Mauvais : "Implémente cette feature"

✅ Bon : "Quels agents peuvent m'aider sur X ?"
✅ Bon : "Quels outils sont disponibles pour Y ?"
✅ Bon : "Comment devrais-je aborder ce problème ?"
```

**Pourquoi ?** Coday connaît mieux ses capacités que vous. Laissez-le vous guider.

---

### 2️⃣ ÊTRE LE GARDE-RAIL

```
❌ Mauvais : Laisser Coday partir dans les buissons
❌ Mauvais : Accepter n'importe quelle suggestion

✅ Bon : Utiliser le vocabulaire technique approprié
✅ Bon : Orienter progressivement avec des prompts clairs
✅ Bon : Corriger les dérives rapidement
✅ Bon : Valider chaque étape avant de continuer
```

**Pourquoi ?** L'IA a besoin de contexte et de direction. Vous êtes le pilote.

---

### 3️⃣ ITÉRATION > BIG BANG

```
❌ Mauvais : "Implémente toute la feature d'un coup"
❌ Mauvais : Une seule grosse PR avec tout

✅ Bon : Trunk-based - petites PR successives
✅ Bon : Feedback Jira après chaque PR
✅ Bon : Prompts par étapes logiques
✅ Bon : Tester et valider à chaque itération
```

**Pourquoi ?** Plus facile à reviewer, débugger, et ajuster en cours de route.

---

## Slide 6 : Exemples de bons prompts

### ❌ Prompts vagues

```
"Fixe ce bug"
"Fais le ticket"
"Améliore la performance"
```

### ✅ Prompts structurés

```
"Analyse le ticket JIRA-123. 
Quels agents sont les plus adaptés pour traiter ce problème ? 
Propose-moi une approche par étapes."

"Je veux créer un service pour gérer les notifications.
Montre-moi d'abord la structure de fichiers existante,
puis propose-moi l'architecture."

"Cette fonction a un problème de performance.
Analyse d'abord le code actuel, identifie les bottlenecks,
puis propose des optimisations."
```

### 🎯 Structure idéale

1. **Contexte** : Où es-tu, que veux-tu faire ?
2. **Question** : Demande de l'aide pour la stratégie
3. **Étapes** : Décompose en actions séquentielles

---

## Slide 7 : Cas pratiques - Front vs Back

### 🎨 Spécificités Front

- **Agents spécialisés** : Utiliser les agents front dédiés
- **Composants Angular** : Vocabulaire précis (controller, service, component)
- **CSS/HTML** : Zone plus compliquée pour l'IA
  - Décrire visuellement ce que vous voulez
  - Valider rapidement le rendu
  - Itérer sur les styles

### ⚙️ Spécificités Back

- **Architecture** : Bien expliciter les patterns (DDD, hexagonal, etc.)
- **Tests** : Demander les tests en même temps que le code
- **Migrations** : Décomposer en étapes (lecture → transformation → écriture)

---

## Slide 8 : Usages avancés

### 📚 Bibliothèque de prompts (Thomas Martin)
- Prompts pré-câblés pour des tâches récurrentes
- Réutilisables et partageables
- Gain de temps énorme

### 🏗️ Agents temporaires (Léo)
- Création d'agents éphémères pour un chantier spécifique
- Configuration ad-hoc
- Suppression après usage

### 💾 Hack de la mémoire (Émilie)
- Optimisation du système de mémoire
- Contexte persistant intelligent
- [À détailler pendant la démo]

### 🎫 Création de tickets Jira
- Générer des tickets en quelques secondes
- Partir de notes brutes ou de discussions
- Structuration automatique

---

## Slide 9 : Organisation de la pratique

### 📍 Par chapitre

**Chapitre Front**
- Benjamin, Léo, Valdes, Émilie
- Focus : composants Angular, agents front

**Chapitre Back**
- Reste de l'équipe dev
- Focus : architecture, services, API

**Autres chapitres**
- Selon composition et besoins

### 🎯 Objectif

- Prendre un ticket réel
- Appliquer le workflow complet
- Partager les difficultés rencontrées
- Échanger les astuces

### ⏱️ Durée : 60-70 minutes

---

## Slide 10 : Support & Suivi

### 💬 Channel Slack dédié
- Questions pendant l'atelier
- Partage de découvertes
- Entraide continue après l'atelier

### 📅 Follow-up
- Sessions par chapitre à organiser
- Vincent A. disponible pour support
- Atelier 3D à venir (Design + Dev + ?)

### 📚 Ressources
- Documentation projet : `./doc/`
- Configuration : `PROJECT_CONFIGURATION.md`
- Ce support : `doc/workshop/atelier-coday-2h.md`

---

## Questions ?

### 🤔 Avant de commencer la démo...

---

# 🎬 FIN DES SLIDES

## Notes pour la démo live

### Ticket de démonstration
- **Type** : Dette technique back
- **Exemple** : Migration converteur → mappeur / abstruct
- **Durée estimée** : 25-30 min
- **Points à montrer** :
  - Sélection de l'agent approprié
  - Prompts progressifs
  - Gestion des dérives
  - Génération de la PR
  - Feedback dans Jira

### Guests à faire intervenir
- **Thomas Martin** (5 min) : bibliothèque de prompts
- **Léo** (10 min) : agents temporaires + démo front
- **Émilie** (3 min) : hack mémoire

### Points de vigilance
- Montrer les fichiers de config en live
- Expliquer User vs Project vs Coday avec des exemples concrets
- Insister sur le rôle de "garde-rail"
- Montrer un cas où ça part dans les buissons et comment corriger

---

## Checklist pré-atelier

- [ ] Environnement de démo prêt
- [ ] Ticket de démo choisi et analysé
- [ ] Guests briefés (timing, contenu)
- [ ] Channel Slack créé et communiqué
- [ ] Invitations envoyées
- [ ] Test du déroulé complet
- [ ] Exemples de bons/mauvais prompts préparés
- [ ] Cas "buissons" identifiés

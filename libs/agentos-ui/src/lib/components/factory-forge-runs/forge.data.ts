import { Workstream } from './forge.model'

/**
 * Jeu de démonstration — à remplacer par la projection lecture de
 * Forge. La forme est celle attendue par les composants : rien à
 * transformer côté vue.
 */
export const DEMO_WORKSTREAMS: readonly Workstream[] = [
  {
    name: 'Talent Portal',
    subject: 'Parcours candidat et espace recruteur',
    lead: 'Un sujet produit et tout ce qui gravite autour : les Epics en cours, mais aussi les comptes rendus de grooming, les décisions et les notes de cadrage.',
    branch: 'ws/talent-portal',
    preview: 'preview.biznet.io/talent-portal',
    docs: [
      {
        category: 'Comptes rendus de grooming',
        items: [
          {
            title: 'Grooming — découpage du parcours candidat',
            meta: '8 sept. · Claire M., agent de planification',
            tag: '3 US créées',
          },
          { title: 'Grooming — filtres partagés', meta: '2 sept. · Claire M., Yanis B.', tag: '2 US créées' },
        ],
      },
      {
        category: 'Décisions',
        items: [
          {
            title: 'Le contrat FiltersFacade reste propriété du domaine partagé',
            meta: '4 sept. · Chapter Lead',
            tag: 'arbitrage',
          },
        ],
      },
      {
        category: 'Notes de cadrage',
        items: [{ title: 'Périmètre du sujet et limites avec Mobile App', meta: '28 août · PO', tag: 'cadrage' }],
      },
    ],
    epics: [
      {
        key: 'FACTORY-SMOKE-FILTERS',
        title: 'Filtres partagés — harmonisation des hôtes',
        closure: 'partial',
        closureSummary:
          "Deux US sur trois ont une preuve de livraison. WZ-1043 n'a pas pu établir la sienne : la clôture reste impossible tant que ce fait n'est pas tranché par un humain.",
        note: "Le statut d'Epic n'est pas une déclaration de livraison : il agrège des preuves d'US. Tant que G4 n'est pas signée, aucune fin d'Epic n'est prononcée.",
        stories: [
          {
            key: 'WZ-1042',
            title: 'Normaliser la signature du composant',
            ticket: 'WZ-1042',
            pr: '#412 draft',
            updatedAt: "aujourd'hui 09:41",
            head: 'code',
            states: {
              discovery: 'na',
              grooming: 'done',
              g1: 'done',
              spec: 'done',
              g2: 'done',
              code: 'running',
              g3: 'pending',
              deploy: 'na',
              g4: 'pending',
              merge: 'pending',
            },
            counters: {
              grooming: [
                { label: 'passes', value: '2' },
                { label: 'durée', value: '11 min' },
                { label: 'coût', value: '~0,18 $' },
              ],
              spec: [
                { label: 'tentatives', value: '1' },
                { label: 'durée', value: '4 min 20 s' },
                { label: 'coût', value: '~0,31 $' },
              ],
              code: [
                { label: 'tentatives', value: '1 / 3' },
                { label: 'durée', value: '6 min 02 s' },
                { label: 'coût', value: '~0,64 $' },
              ],
            },
            query: {
              step: 'code',
              where: 'phase Code · 4b Édition',
              since: '35 min',
              text: "La spec autorise filters.component.ts mais le contrat FiltersFacade est aussi consommé par sprint-admin. Dois-je étendre la signature aux deux hôtes, ou m'en tenir à sprint-web comme indiqué dans le périmètre ?",
              options: ['\u00c9tendre aux deux h\u00f4tes', "S'en tenir au p\u00e9rim\u00e8tre"],
            },
            detail: {
              code: [
                {
                  steps: [
                    {
                      label: '4a · Baseline',
                      actor: 'Factory',
                      state: 'done',
                      does: 'Empreinte du dépôt prise avant toute écriture.',
                      metric: 'dépôt propre · 1 284 fichiers empreintés',
                      evidence: [
                        { label: 'état', value: 'BASELINED' },
                        { label: 'référence', value: 'sha256:02f7be41…c19d0a' },
                      ],
                    },
                    {
                      label: '4b · Édition',
                      actor: 'Agent éditeur',
                      state: 'human',
                      does: "L'agent attend une réponse pour poursuivre.",
                      metric: 'en pause · question posée il y a 35 min',
                      evidence: [
                        { label: 'exécution', value: 'edit_794c930a…67df6bfcb86b' },
                        { label: 'fichiers touchés', value: 'filters.component.ts, filters.facade.ts' },
                        { label: 'état', value: 'queryUser — agent en attente' },
                      ],
                    },
                    {
                      label: '4c · Claims',
                      actor: 'Factory',
                      state: 'pending',
                      does: 'Comparera les fichiers déclarés au diff réel.',
                      metric: 'non démarré',
                      evidence: [{ label: 'règle', value: 'écart enregistré comme fait, visible mais non bloquant' }],
                    },
                  ],
                },
              ],
            },
          },
          {
            key: 'WZ-1043',
            title: 'Étendre les filtres aux vues admin',
            ticket: 'WZ-1043',
            pr: '#408 review',
            updatedAt: 'hier 16:08',
            head: 'g3',
            states: {
              discovery: 'na',
              grooming: 'done',
              g1: 'done',
              spec: 'done',
              g2: 'done',
              code: 'done',
              g3: 'blocked',
              deploy: 'na',
              g4: 'pending',
              merge: 'pending',
            },
            counters: {
              code: [
                { label: 'tentatives', value: '2 / 3' },
                { label: 'durée', value: '9 min 44 s' },
                { label: 'coût', value: '~1,12 $' },
              ],
              g3: [
                { label: 'tentatives', value: '3 / 3' },
                { label: 'durée', value: '2 min 51 s' },
                { label: 'coût', value: '~0,22 $' },
              ],
            },
            decisions: {
              g3: {
                lead: 'Budget de retry épuisé et preuve de tests non établie. Trois issues, toutes tracées dans le ledger.',
                options: [
                  { label: "Relancer l'éditeur", kind: 'retry' },
                  { label: 'Ignorer le finding', kind: 'ignore' },
                  { label: 'Confirmer le FAIL', kind: 'confirm' },
                ],
              },
            },
          },
          {
            key: 'WZ-1044',
            title: 'Couvrir les filtres par des tests unitaires',
            ticket: 'WZ-1044',
            pr: '#401 merged',
            updatedAt: 'hier 11:22',
            head: 'g3',
            states: {
              discovery: 'na',
              grooming: 'done',
              g1: 'done',
              spec: 'done',
              g2: 'done',
              code: 'done',
              g3: 'done',
              deploy: 'na',
              g4: 'pending',
              merge: 'pending',
            },
            counters: {
              g3: [
                { label: 'tentatives', value: '1 / 3' },
                { label: 'durée', value: '2 min 32 s' },
                { label: 'coût', value: '~0,19 $' },
              ],
            },
          },
        ],
      },
      {
        key: 'FACTORY-EXPORT-CSV',
        title: 'Export CSV des tableaux de bord',
        closure: 'pending',
        closureSummary: "Aucune US n'a encore de preuve de livraison : la clôture n'est pas envisageable.",
        note: "Epic jeune : une US attend une décision humaine à G1, l'autre est encore en grooming.",
        stories: [
          {
            key: 'WZ-1101',
            title: "Contrat d'export côté domaine",
            ticket: 'WZ-1101',
            pr: '#417 draft',
            updatedAt: 'il y a 2 j',
            head: 'g1',
            states: {
              discovery: 'na',
              grooming: 'done',
              g1: 'human',
              spec: 'pending',
              g2: 'pending',
              code: 'pending',
              g3: 'pending',
              deploy: 'na',
              g4: 'pending',
              merge: 'pending',
            },
            counters: {
              grooming: [
                { label: 'passes', value: '3' },
                { label: 'durée', value: '22 min' },
                { label: 'coût', value: '~0,29 $' },
              ],
            },
            decisions: {
              g1: {
                lead: 'En attente de Claire M. (PO Talent Portal) depuis 2 jours. Tout le workflow de cette US est suspendu à cette décision.',
                options: [
                  { label: "Approuver l'intention", kind: 'approve' },
                  { label: 'Rejeter', kind: 'reject' },
                ],
              },
            },
          },
          {
            key: 'WZ-1102',
            title: "Bouton d'export dans la barre d'outils",
            ticket: 'WZ-1102',
            pr: '—',
            updatedAt: 'il y a 2 j',
            head: 'grooming',
            states: {
              discovery: 'na',
              grooming: 'running',
              g1: 'pending',
              spec: 'pending',
              g2: 'pending',
              code: 'pending',
              g3: 'pending',
              deploy: 'na',
              g4: 'pending',
              merge: 'pending',
            },
          },
        ],
      },
    ],
  },
  {
    name: 'Mobile App',
    subject: 'Application mobile terrain',
    lead: "Sujet ouvert récemment : une Epic engagée, peu de matière documentaire pour l'instant.",
    branch: 'ws/mobile-app',
    preview: 'preview.biznet.io/mobile',
    docs: [
      {
        category: 'Comptes rendus de grooming',
        items: [{ title: "Grooming — journal d'audit", meta: '6 sept. · agent de planification', tag: '1 US créée' }],
      },
    ],
    epics: [
      {
        key: 'FACTORY-AUDIT-LOG',
        title: "Journal d'audit consultable",
        closure: 'blocked',
        closureSummary:
          "Dépendance non résolue côté Talent Portal : la clôture est bloquée indépendamment des preuves d'US.",
        note: "Une exécution a été interrompue : la reprise est possible à l'étape où elle s'est arrêtée, sans tout regénérer.",
        stories: [
          {
            key: 'WZ-1150',
            title: 'Modèle de journal',
            ticket: 'WZ-1150',
            pr: '#420 draft',
            updatedAt: 'il y a 5 j',
            head: 'spec',
            resumeAt: 'la phase Specification (3b, production de la spec)',
            states: {
              discovery: 'na',
              grooming: 'done',
              g1: 'done',
              spec: 'stopped',
              g2: 'pending',
              code: 'pending',
              g3: 'pending',
              deploy: 'na',
              g4: 'pending',
              merge: 'pending',
            },
            counters: {
              spec: [
                { label: 'tentatives', value: '1 / 3' },
                { label: 'durée', value: '1 min 12 s' },
                { label: 'coût', value: '~0,08 $' },
              ],
            },
          },
        ],
      },
    ],
  },
]

/**
 * Sous-étapes par défaut, utilisées quand une US n'a pas de détail
 * propre pour une étape : la mécanique reste lisible, portée au
 * statut de l'étape.
 */
export const GENERIC_SUBSTEPS: Readonly<
  Record<
    string,
    readonly { title?: string; note?: string; steps: readonly Omit<import('./forge.model').SubStep, 'state'>[] }[]
  >
> = {
  discovery: [
    {
      steps: [
        {
          label: 'Hypothèse produit',
          actor: 'Produit',
          does: "Formule ce qu'on veut apprendre et élimine les pistes faibles.",
          evidence: [{ label: 'état', value: "non automatisé — représenté pour situer l'amont" }],
        },
      ],
    },
  ],
  grooming: [
    {
      steps: [
        {
          label: '2a · Décomposition',
          actor: 'Agent de planification',
          does: "Génère le découpage en US à partir de l'intention produit ; le PO challenge et ajuste.",
          evidence: [
            { label: 'produit', value: 'liste des US avec leur périmètre' },
            { label: 'commit', value: 'artefacts de planification sur le dépôt' },
          ],
        },
        {
          label: '2b · Arbitrage',
          actor: 'PO',
          does: "Fixe l'ordre de traitement, identifie les US bloquantes, fusionne ou découpe si besoin.",
          evidence: [{ label: 'produit', value: 'ordre et dépendances' }],
        },
      ],
    },
  ],
  g1: [
    {
      steps: [
        {
          label: "Approbation d'intention",
          actor: 'Humain autorisé',
          does: 'Approuve périmètre, priorité et ressources engagées.',
          evidence: [
            { label: 'entrée', value: 'spec Epic + liste des US + snapshot du ticket' },
            { label: 'sortie', value: 'décision signée dans le ledger' },
          ],
        },
      ],
    },
  ],
  spec: [
    {
      steps: [
        {
          label: '3a · Analyse',
          actor: 'Agent analyste — lecture seule',
          does: "Lit le dépôt, les specs existantes et l'historique du ticket, puis produit un plan d'analyse.",
          evidence: [
            { label: 'droits', value: 'aucune écriture à ce stade' },
            { label: 'produit', value: 'fichiers concernés, dépendances, risques' },
          ],
        },
        {
          label: '3b · Production de la spec',
          actor: 'Agent analyste',
          does: 'Rédige la spec technique avec allow, create et deny, puis la commite sur la branche du ticket.',
          evidence: [
            { label: 'produit', value: 'forge/specs/<ticket>.md' },
            { label: 'référence', value: 'hash SHA-256 immuable' },
          ],
        },
      ],
    },
  ],
  g2: [
    {
      steps: [
        {
          label: 'Revue de spec',
          actor: 'Reviewers en parallèle — lecture seule',
          does: 'Cherchent contradiction de périmètre, impact sécurité, incohérence de law.',
          evidence: [
            { label: 'verdicts', value: 'approuvé · demande de changements · rejeté' },
            { label: 'véto critique', value: 'escalade humaine obligatoire' },
          ],
        },
      ],
    },
  ],
  code: [
    {
      steps: [
        {
          label: '4a · Baseline',
          actor: 'Factory',
          does: 'Prend une empreinte SHA-256 du dépôt avant toute modification. Dépôt non propre \u2192 UNBASELINED, blocage.',
          evidence: [{ label: 'produit', value: 'empreintes de référence' }],
        },
        {
          label: '4b · \u00c9dition',
          actor: 'Agent éditeur',
          does: 'Produit les changements dans le périmètre de la spec, puis commit automatique.',
          evidence: [{ label: 'droits', value: 'allow + create uniquement' }],
        },
        {
          label: '4c · Claims',
          actor: 'Factory',
          does: "Compare les fichiers déclarés par l'éditeur au diff réel. Un écart est un fait enregistré, visible.",
          evidence: [{ label: 'règle', value: 'écart non bloquant mais toujours visible' }],
        },
      ],
    },
  ],
  g3: [
    {
      title: '5a · Oracles déterministes',
      note: 'la Factory exécute — aucun agent ne peut transformer un FAIL en PASS',
      steps: [
        {
          label: 'back.build',
          actor: 'Factory',
          does: 'Compilation du backend.',
          evidence: [{ label: 'verdict', value: 'exitCode === 0' }],
        },
        {
          label: 'front.build',
          actor: 'Factory',
          does: 'Build du frontend.',
          evidence: [{ label: 'verdict', value: 'exitCode === 0' }],
        },
        {
          label: 'front.tests',
          actor: 'Factory',
          does: 'Tests frontend ciblés sur les fichiers modifiés.',
          evidence: [
            { label: 'verdict', value: 'exitCode === 0' },
            { label: 'timeout ou crash', value: 'FAIL — jamais PASS par défaut' },
          ],
        },
      ],
    },
    {
      title: '5c · Revue adversariale',
      note: 'jugement, pas mesure',
      steps: [
        {
          label: 'Reviewers',
          actor: 'Agents en parallèle — lecture seule',
          does: "Examinent diff, spec et résultats d'oracle : cohérence, sécurité, compatibilité, périmètre.",
          evidence: [
            { label: 'véto critique', value: 'rejet immédiat' },
            { label: 'finding majeur', value: 'request-changes \u2192 retry éditeur' },
          ],
        },
      ],
    },
  ],
  deploy: [
    {
      steps: [
        {
          label: 'Pipeline preview',
          actor: 'CI/CD COPS',
          does: 'Déploie la branche du workstream sur la preview partagée.',
          evidence: [{ label: 'état', value: 'non disponible — étape grisée' }],
        },
      ],
    },
  ],
  g4: [
    {
      steps: [
        {
          label: "Cl\u00f4ture d'Epic",
          actor: 'Humain — gouvernance',
          does: 'Agrège les preuves des US et signe la clôture.',
          evidence: [{ label: 'états', value: 'passed \u00b7 partial \u00b7 failed \u00b7 blocked' }],
        },
      ],
    },
  ],
  merge: [
    {
      steps: [
        {
          label: 'Merge de la PR',
          actor: 'GitHub',
          does: 'La PR draft passe en review finale puis est fusionnée.',
          evidence: [{ label: 'surface', value: 'diffs, commentaires et approbations sur GitHub' }],
        },
      ],
    },
  ],
}

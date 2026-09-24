/* ------------------------------------------------------------------
 * Forge console — modèle de domaine
 *
 * Le vocabulaire est celui de la Factory : un Workstream porte des
 * Epics et sa matière documentaire ; une Epic porte des US ; une US
 * traverse des phases et des gates. Les gates sont des étapes à part
 * entière, pas des séparateurs.
 * ------------------------------------------------------------------ */

export type RunState =
  | 'done'
  | 'running'
  | 'human'
  | 'review'
  | 'blocked'
  | 'failed'
  | 'prior'
  | 'stopped'
  | 'pending'
  | 'na'

export interface Tone {
  /** Remplissage : une hachure pour les états indéterminés. */
  readonly bg: string
  readonly ink: string
  /** Le mot toujours écrit — la couleur ne fait que le renforcer. */
  readonly word: string
}

export const TONES: Readonly<Record<RunState, Tone>> = {
  done: { bg: '#dcece2', ink: '#23543f', word: 'passé' },
  running: { bg: '#d9e8f5', ink: '#2c455d', word: 'en cours' },
  human: { bg: '#f4e8d2', ink: '#6f4a10', word: 'attente humaine' },
  review: {
    bg: 'repeating-linear-gradient(45deg, #e3ecf5, #e3ecf5 5px, #cddfef 5px, #cddfef 10px)',
    ink: '#2c455d',
    word: 'en revue',
  },
  blocked: {
    bg: 'repeating-linear-gradient(45deg, #f4e8d2, #f4e8d2 5px, #e6d4b0 5px, #e6d4b0 10px)',
    ink: '#6f4a10',
    word: 'bloqué',
  },
  failed: { bg: '#f5dcda', ink: '#7d2b29', word: 'échec' },
  prior: { bg: '#f6ece0', ink: '#7a5a2a', word: 'diagnostic préexistant' },
  stopped: {
    bg: 'repeating-linear-gradient(45deg, #e7e7ea, #e7e7ea 5px, #d5d5d8 5px, #d5d5d8 10px)',
    ink: '#5d5d60',
    word: 'interrompu',
  },
  pending: { bg: '#e7e7ea', ink: '#5d5d60', word: 'non démarré' },
  na: { bg: '#eaeaec', ink: '#5d5d60', word: 'non implémenté' },
}

export type StepKey = 'discovery' | 'grooming' | 'g1' | 'spec' | 'g2' | 'code' | 'g3' | 'deploy' | 'g4' | 'merge'

export type StepLevel = 'Workstream' | 'Epic' | 'US'

export interface WorkflowStep {
  readonly key: StepKey
  /** « Phase 4 · agent éditeur, writable » — numéro et nature. */
  readonly kind: string
  readonly name: string
  /** Nom complet affiché dans le volet de détail. */
  readonly full?: string
  /** Abréviation pour les rubans denses. */
  readonly short: string
  /** Largeur relative dans le ruban : les gates sont étroits. */
  readonly weight: number
  readonly level: StepLevel
  readonly face: string
  readonly faceInk: string
  readonly owner: string
  readonly lead: string
  readonly out: string
}

/**
 * Les dix étapes du workflow, dans l'ordre. `face` vient de la rampe
 * acier : les phases montent en intensité, les gates portent la teinte
 * profonde pour se distinguer des phases qu'ils séparent.
 */
export const STEPS: readonly WorkflowStep[] = [
  {
    key: 'discovery',
    kind: 'Phase 1 · future, grisée',
    name: 'Discovery',
    full: 'Discovery / Intent',
    short: 'Discov.',
    weight: 1,
    level: 'Epic',
    face: 'var(--color-neutral-200)',
    faceInk: 'var(--color-neutral-700)',
    owner: 'Produit — non automatisé',
    lead: "Au niveau de l'Epic : formulation d'une hypothèse produit — ce qu'on veut apprendre, ce qu'on élimine, l'intention retenue. Représentée pour situer l'amont ; la Factory n'y intervient pas encore.",
    out: "Sortie : une intention d'Epic formalisée, prête à être découpée.",
  },
  {
    key: 'grooming',
    kind: 'Phase 2 · agents + humain',
    name: 'Grooming',
    short: 'Grooming',
    weight: 1.25,
    level: 'US',
    face: 'var(--color-accent-200)',
    faceInk: 'var(--color-text)',
    owner: 'Agent de planification + PO',
    lead: "Premier moment où la Factory entre en jeu. Le découpage porte sur l'Epic, mais chaque US est ensuite groomée pour elle-même : spécification initiale, périmètre, place dans l'ordre de traitement.",
    out: 'Sortie : US créées dans le ticketing, artefacts de planification commités sur le dépôt.',
  },
  {
    key: 'g1',
    kind: 'Gate 1 · humain obligatoire',
    name: 'G1',
    full: 'G1 · Intention approuvée',
    short: 'G1',
    weight: 0.52,
    level: 'Epic',
    face: 'var(--color-accent-900)',
    faceInk: 'var(--color-bg)',
    owner: 'Product Owner ou Chapter Lead',
    lead: "Un humain valide l'intention de l'Epic avant de lancer les US : périmètre, priorité, ressources. Aucune automatisation possible — ce gate ne peut jamais être franchi par un agent ni par une règle.",
    out: "Sortie : décision signée dans le ledger — approuvé, rejeté ou en attente. Le reste du workflow est bloqué tant qu'elle n'est pas prise.",
  },
  {
    key: 'spec',
    kind: 'Phase 3 · agent analyste, read-only',
    name: 'Specification',
    short: 'Spec',
    weight: 1.15,
    level: 'US',
    face: 'var(--color-accent-300)',
    faceInk: 'var(--color-text)',
    owner: 'Agent analyste — lecture seule',
    lead: "Un agent analyste explore le code existant et produit la spécification technique de l'US : fichiers à modifier, impacts, contrats. Markdown + frontmatter YAML, commité sur la branche du ticket.",
    out: 'Sortie : forge/specs/<ticket>.md, hash SHA-256, commit automatique.',
  },
  {
    key: 'g2',
    kind: 'Gate 2 · déterministe + reviewers',
    name: 'G2',
    full: 'G2 · Spec validée',
    short: 'G2',
    weight: 0.52,
    level: 'US',
    face: 'var(--color-accent-900)',
    faceInk: 'var(--color-bg)',
    owner: 'Agents reviewers en parallèle',
    lead: 'Vérification que la spec est cohérente, dans le bon périmètre, sans contradiction de law. Des reviewers en lecture seule passent en parallèle ; un véto critique renvoie en phase Spec ou escalade.',
    out: 'Verdicts : approuvé → Code · demande de changements → retour en Spec dans le budget · rejeté → escalade humaine.',
  },
  {
    key: 'code',
    kind: 'Phase 4 · agent éditeur, writable',
    name: 'Code',
    short: 'Code',
    weight: 1.15,
    level: 'US',
    face: 'var(--color-accent-700)',
    faceInk: 'var(--color-bg)',
    owner: 'Agent éditeur — lecture + écriture bornée',
    lead: "L'éditeur ne modifie que les fichiers déclarés dans allow et create de la spec. Il ne peut ni lancer de build, ni lancer de tests, ni appeler de sous-agent : la vérification ne lui appartient pas.",
    out: 'Sortie : code commité sur la branche du ticket, diff SHA-256 et claims enregistrés dans le ledger.',
  },
  {
    key: 'g3',
    kind: 'Gate 3 · oracles + revue adversariale',
    name: 'G3',
    full: 'G3 · Livraison validée',
    short: 'G3',
    weight: 0.52,
    level: 'US',
    face: 'var(--color-accent-900)',
    faceInk: 'var(--color-bg)',
    owner: 'Factory + agents reviewers',
    lead: 'Le gate le plus riche : la Factory — pas les agents — exécute les oracles, puis des reviewers examinent la livraison. Un oracle FAIL reste FAIL : aucun agent ne peut le transformer en PASS.',
    out: 'Verdicts : PASS si tous les oracles sont verts et sans véto · request-changes dans le budget · FAIL persistant → escalade humaine.',
  },
  {
    key: 'deploy',
    kind: 'Phase 5 · CI/CD COPS, grisée',
    name: 'Deploy',
    full: 'Deploy preview',
    short: 'Deploy',
    weight: 1,
    level: 'US',
    face: 'var(--color-neutral-200)',
    faceInk: 'var(--color-neutral-700)',
    owner: 'CI/CD COPS — non disponible',
    lead: "Le pipeline déploie la branche du workstream sur un environnement de preview partagé. Ce n'est pas une URL par US : toutes les US du workstream cohabitent sur la même preview.",
    out: "Sortie : preview accessible à toute l'équipe, avec la liste des US embarquées.",
  },
  {
    key: 'g4',
    kind: 'Gate 4 · humain obligatoire',
    name: 'G4',
    full: 'G4 · Clôture Epic',
    short: 'G4',
    weight: 0.52,
    level: 'Epic',
    face: 'var(--color-accent-900)',
    faceInk: 'var(--color-bg)',
    owner: 'Product Owner — gouvernance',
    lead: "La Factory agrège les preuves de toutes les US de l'Epic et vérifie qu'elles sont présentes ; un humain signe la clôture. Feature-flaggé : hors MVP.",
    out: "États : passed · partial · failed · blocked, selon la politique de l'Epic.",
  },
  {
    key: 'merge',
    kind: 'Phase 6 · GitHub',
    name: 'Merge',
    short: 'Merge',
    weight: 1,
    level: 'US',
    face: 'var(--color-neutral-200)',
    faceInk: 'var(--color-neutral-700)',
    owner: 'GitHub — surface de collaboration',
    lead: 'La branche du workstream est mergée sur la principale. La PR, ouverte en draft dès le début du ticket, passe en review finale.',
    out: 'Sortie : PR fusionnée, chaîne de preuves close et auditable.',
  },
]

/**
 * Les étapes portées par une US. Discovery en est exclue : c'est une
 * phase de niveau Epic, et un diagramme d'US ne doit pas donner à
 * croire qu'une US puisse s'y trouver.
 */
export const US_STEPS: readonly WorkflowStep[] = STEPS.filter((s) => s.key !== 'discovery')

export const STEP_BY_KEY: Readonly<Record<StepKey, WorkflowStep>> = Object.fromEntries(
  STEPS.map((s) => [s.key, s])
) as Record<StepKey, WorkflowStep>

/* ------------------------------------------------------------------
 * Sous-étapes et preuves
 * ------------------------------------------------------------------ */

export interface EvidenceEntry {
  readonly label: string
  readonly value: string
}

export interface SubStep {
  readonly label: string
  readonly actor: string
  readonly does: string
  readonly state?: RunState
  /** Information synthétique : durée, exit code, nombre de fichiers. */
  readonly metric?: string
  /** Écart mesuré avant / après édition (oracles de G3). */
  readonly baseline?: string
  readonly after?: string
  readonly evidence: readonly EvidenceEntry[]
}

/** Un bloc de sous-étapes : `title` sépare 5a, 5b, 5c, 5d dans G3. */
export interface SubStepGroup {
  readonly title?: string
  readonly note?: string
  readonly steps: readonly SubStep[]
}

export interface AgentQuery {
  readonly step: StepKey
  readonly where: string
  readonly since: string
  readonly text: string
  readonly options: readonly string[]
}

export type DecisionKind = 'approve' | 'reject' | 'retry' | 'ignore' | 'confirm' | 'answer'

export interface DecisionPanel {
  readonly lead: string
  readonly options: readonly { readonly label: string; readonly kind: DecisionKind }[]
}

export interface Counter {
  readonly label: string
  readonly value: string
}

export interface StoryRun {
  readonly key: string
  readonly title: string
  readonly ticket: string
  readonly pr: string
  readonly updatedAt: string
  /** Position déclarée ; corrigée par `headOf` si elle tombe sur `na`. */
  readonly head: StepKey
  /** Étape où une exécution interrompue peut reprendre. */
  readonly resumeAt?: string
  readonly states: Readonly<Partial<Record<StepKey, RunState>>>
  readonly counters?: Readonly<Partial<Record<StepKey, readonly Counter[]>>>
  readonly detail?: Readonly<Partial<Record<StepKey, readonly SubStepGroup[]>>>
  readonly decisions?: Readonly<Partial<Record<StepKey, DecisionPanel>>>
  readonly query?: AgentQuery
}

export type EpicClosure = 'passed' | 'partial' | 'failed' | 'blocked' | 'pending'

export interface EpicRun {
  readonly key: string
  readonly title: string
  readonly closure: EpicClosure
  readonly closureSummary: string
  readonly note: string
  readonly stories: readonly StoryRun[]
}

export interface WorkstreamDoc {
  readonly title: string
  readonly meta: string
  readonly tag: string
}

export interface WorkstreamDocGroup {
  readonly category: string
  readonly items: readonly WorkstreamDoc[]
}

export interface Workstream {
  readonly name: string
  readonly subject: string
  readonly lead: string
  readonly branch: string
  readonly preview: string
  readonly epics: readonly EpicRun[]
  readonly docs: readonly WorkstreamDocGroup[]
}

/* ------------------------------------------------------------------
 * Règles de lecture — partagées par toutes les échelles
 * ------------------------------------------------------------------ */

export function stateOf(story: StoryRun, key: StepKey, overrides?: Partial<Record<StepKey, RunState>>): RunState {
  return overrides?.[key] ?? story.states[key] ?? 'pending'
}

/**
 * Position réelle d'une US. Une US ne peut pas se trouver dans une
 * étape non implémentée : on retombe sur la dernière étape réellement
 * observée.
 */
export function headOf(story: StoryRun, overrides?: Partial<Record<StepKey, RunState>>): StepKey {
  if (stateOf(story, story.head, overrides) !== 'na') return story.head
  const seen = US_STEPS.map((s) => s.key).filter((k) => {
    const value = stateOf(story, k, overrides)
    return value !== 'na' && value !== 'pending'
  })
  return seen.length ? seen[seen.length - 1]! : US_STEPS[0]!.key
}

export function toneOf(state: RunState): Tone {
  return TONES[state]
}

export function plural(count: number, one: string, many: string): string {
  return `${count} ${count > 1 ? many : one}`
}

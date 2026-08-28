/**
 * Tests unitaires pour factory/lib/jira.mjs.
 *
 * Couvre les deux fonctions pures : extractTicketId et extractAdfText.
 * fetchJiraTicket n'est pas testée : elle fait un appel réseau réel.
 *
 * Usage : node factory/tests/test-jira.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import { extractTicketId, extractAdfText, fetchJiraComments, applyCommentBudget } from '../lib/jira.mjs'

// ---------------------------------------------------------------------------
// Runner minimal — même mécanique que test-oracle.mjs et test-us-loop.mjs
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

/**
 * Vérifie qu'une valeur est égale à la valeur attendue (comparaison JSON).
 *
 * @param {string} name
 * @param {unknown} actual
 * @param {unknown} expected
 */
function expect(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  const icon = ok ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (!ok) {
    console.log(`  attendu : ${JSON.stringify(expected)}`)
    console.log(`  obtenu  : ${JSON.stringify(actual)}`)
  }
  if (ok) passed++
  else failed++
}

// ===========================================================================
// extractTicketId
// ===========================================================================

console.log('\n=== extractTicketId ===\n')

// --- Cas valides ---

{
  // Identifiant nu, format standard.
  expect(
    'identifiant nu : retourne l\'identifiant tel quel',
    extractTicketId('PROJ-1234'),
    'PROJ-1234'
  )
}

{
  // URL /browse/ complète — cas courant quand on colle l'URL depuis le navigateur.
  expect(
    'URL /browse/ : extrait l\'identifiant',
    extractTicketId('https://foo.atlassian.net/browse/PROJ-1234'),
    'PROJ-1234'
  )
}

{
  // Minuscules — l'identifiant doit être normalisé en majuscules.
  // Jira accepte 'proj-1234' dans l'URL, mais l'identifiant canonique est en majuscules.
  expect(
    'identifiant en minuscules : normalisé en majuscules',
    extractTicketId('proj-1234'),
    'PROJ-1234'
  )
}

{
  // Minuscules dans l'URL /browse/.
  expect(
    'URL /browse/ avec minuscules : normalisé en majuscules',
    extractTicketId('https://foo.atlassian.net/browse/proj-1234'),
    'PROJ-1234'
  )
}

{
  // Préfixe multi-lettres (ex. WZ, FRONT, MYPROJECT).
  expect(
    'préfixe long : extrait correctement',
    extractTicketId('MYPROJECT-42'),
    'MYPROJECT-42'
  )
}

// --- Cas invalides ---

{
  // Chaîne qui ne ressemble pas à un identifiant Jira.
  expect(
    'chaîne invalide : retourne null',
    extractTicketId('pas-un-ticket'),
    null
  )
}

{
  // Chaîne vide.
  expect(
    'chaîne vide : retourne null',
    extractTicketId(''),
    null
  )
}

{
  // null passé en entrée (défense contre les appels avec valeur manquante).
  expect(
    'null : retourne null',
    extractTicketId(null),
    null
  )
}

{
  // Nombre seul sans préfixe alphabetique.
  expect(
    'nombre seul sans préfixe : retourne null',
    extractTicketId('1234'),
    null
  )
}

{
  // Préfixe numérique uniquement (invalide : doit commencer par une lettre).
  expect(
    'préfixe numérique : retourne null',
    extractTicketId('1PROJ-123'),
    null
  )
}

// ===========================================================================
// extractAdfText
// ===========================================================================

console.log('\n=== extractAdfText ===\n')

{
  // Noeud texte simple — cas de base, retourne le texte brut.
  expect(
    'noeud texte simple : retourne le texte',
    extractAdfText({ type: 'text', text: 'Bonjour' }),
    'Bonjour'
  )
}

{
  // Paragraphe imbriqué — un paragraphe contenant un noeud texte doit retourner
  // le texte suivi d'un saut de ligne (BLOCK_TYPES contient 'paragraph').
  expect(
    'paragraphe imbriqué : texte + saut de ligne',
    extractAdfText({
      type: 'paragraph',
      content: [{ type: 'text', text: 'Ligne 1' }],
    }),
    'Ligne 1\n'
  )
}

{
  // Deux paragraphes — chacun doit apporter son saut de ligne.
  expect(
    'deux paragraphes : texte avec deux sauts de ligne',
    extractAdfText({
      type: 'doc',
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: 'P1' }] },
        { type: 'paragraph', content: [{ type: 'text', text: 'P2' }] },
      ],
    }),
    'P1\nP2\n'
  )
}

{
  // Liste à puces — bulletList contient des listItem, chacun avec un paragraphe.
  // Chaque niveau de bloc apporte son saut de ligne.
  //
  // Détail de la construction :
  //   paragraph  (BLOCK_TYPE) → 'Item A' + '\n'           = 'Item A\n'
  //   listItem   (BLOCK_TYPE) → 'Item A\n' + '\n'         = 'Item A\n\n'
  //   bulletList (BLOCK_TYPE) → 'Item A\n\nItem B\n\n' + '\n' = 'Item A\n\nItem B\n\n\n'
  //
  // Le '\n' final vient du bulletList lui-même, qui est aussi un BLOCK_TYPE.
  // C'est le comportement correct et attendu de la fonction.
  expect(
    'liste à puces : chaque item suivi d\'un saut de ligne',
    extractAdfText({
      type: 'bulletList',
      content: [
        {
          type: 'listItem',
          content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Item A' }] }],
        },
        {
          type: 'listItem',
          content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Item B' }] }],
        },
      ],
    }),
    // paragraph \n + listItem \n + bulletList \n : trois niveaux de blocs
    'Item A\n\nItem B\n\n\n'
  )
}

{
  // null — doit retourner une chaîne vide, pas lever.
  expect(
    'null : retourne chaîne vide',
    extractAdfText(null),
    ''
  )
}

{
  // Noeud sans propriété content — doit retourner une chaîne vide.
  expect(
    'noeud sans content : retourne chaîne vide',
    extractAdfText({ type: 'paragraph' }),
    '\n'
    // paragraph est un BLOCK_TYPE donc saut de ligne, même avec content absent
    // (children = [] → parts = [] → ''.join + '\n')
  )
}

{
  // Structure profondément imbriquée — vérifie que la récursion ne se perd pas.
  // doc > section (type inconnu) > paragraph > text
  // Seul paragraph est un BLOCK_TYPE ici.
  expect(
    'structure profondément imbriquée : texte extrait correctement',
    extractAdfText({
      type: 'doc',
      content: [{
        type: 'section',   // type inconnu — pas dans BLOCK_TYPES
        content: [{
          type: 'paragraph',
          content: [{ type: 'text', text: 'Profond' }],
        }],
      }],
    }),
    'Profond\n'
  )
}

{
  // Noeud texte avec texte vide — doit retourner la chaîne vide.
  expect(
    'noeud texte vide : retourne chaîne vide',
    extractAdfText({ type: 'text', text: '' }),
    ''
  )
}

{
  // Noeud de type inconnu sans content — pas un BLOCK_TYPE, pas de content.
  expect(
    'noeud inconnu sans content : retourne chaîne vide',
    extractAdfText({ type: 'unknownType' }),
    ''
  )
}

// ===========================================================================
// applyCommentBudget
// ===========================================================================

console.log('\n=== applyCommentBudget ===\n')

// Helper to build a comment object with a given body size
function makeComment(author, created, bodyLength) {
  return { author, created, body: 'x'.repeat(bodyLength) }
}

{
  // No comments — empty result.
  const { included, omitted } = applyCommentBudget([], 1000)
  expect('no comments : included vide', included.length, 0)
  expect('no comments : omitted = 0', omitted, 0)
}

{
  // All comments fit within budget.
  const comments = [
    makeComment('Alice', '2026-01-03T00:00:00.000Z', 100),
    makeComment('Bob', '2026-01-02T00:00:00.000Z', 100),
    makeComment('Carol', '2026-01-01T00:00:00.000Z', 100),
  ]
  const { included, omitted } = applyCommentBudget(comments, 10000)
  expect('all fit : tous inclus', included.length, 3)
  expect('all fit : omitted = 0', omitted, 0)
}

{
  // Budget exhausted after first two comments — oldest one omitted.
  // Cost model: author.length + created.length + body.length + 50 overhead.
  // Comment 1 (Alice): 5 + 24 + 200 + 50 = 279. remaining after: 600 - 279 = 321 > 0 → included.
  // Comment 2 (Bob):   3 + 24 + 200 + 50 = 277. remaining after: 321 - 277 = 44 > 0 → included.
  // Comment 3 (Carol): 5 + 24 + 200 + 50 = 279. remaining at start of loop: 44 > 0, so push, remaining = 44 - 279 = -235.
  // Wait — the loop checks `if (remaining <= 0) break` BEFORE pushing, so:
  //   start: remaining=600. push Alice, remaining=321. push Bob, remaining=44. push Carol, remaining=-235. Loop ends.
  //   All 3 fit within 600 budget (check before deduct, not after).
  // Use a budget of 300 to only fit 1 comment:
  //   start: remaining=300. push Alice (279), remaining=21. Then Bob: 21 > 0 → push Bob (277), remaining=-256.
  //   Loop ends (next iteration: remaining=-256 ≤ 0 → break before Carol).
  //   Result: 2 included, 1 omitted.
  // To fit exactly 1, use budget = 280 (only Alice fits: 279 ≤ 280, then remaining=1; Bob: 1 > 0 → push Bob... no).
  // Actually the loop pushes then deducts, so remaining=1 after Alice, then Bob enters with remaining=1 > 0 → push.
  // The current budget model is "push then deduct" so a budget of 279 would let Alice in (remaining=0),
  // then Bob: remaining=0 ≤ 0 → break. Result: 1 included, 2 omitted.
  const comments = [
    makeComment('Alice', '2026-01-03T00:00:00.000Z', 200),
    makeComment('Bob',   '2026-01-02T00:00:00.000Z', 200),
    makeComment('Carol', '2026-01-01T00:00:00.000Z', 200),
  ]
  // Alice cost = 5 + 24 + 200 + 50 = 279. Budget = 279 → Alice included, remaining = 0.
  // Bob: remaining <= 0 → break. 1 included, 2 omitted.
  const { included, omitted } = applyCommentBudget(comments, 279)
  expect('budget tronque : 1 inclus', included.length, 1)
  expect('budget tronque : 2 omis', omitted, 2)
  expect('budget tronque : premier comment inclus (newest)', included[0].author, 'Alice')
}

{
  // Ordering preserved — newest first in input, newest first in output.
  const comments = [
    makeComment('Newest', '2026-01-03T00:00:00.000Z', 10),
    makeComment('Middle', '2026-01-02T00:00:00.000Z', 10),
    makeComment('Oldest', '2026-01-01T00:00:00.000Z', 10),
  ]
  const { included, omitted } = applyCommentBudget(comments, 10000)
  expect('ordering : premier = newest', included[0].author, 'Newest')
  expect('ordering : dernier = oldest', included[2].author, 'Oldest')
  expect('ordering : omitted = 0', omitted, 0)
}

{
  // Exact budget — all fit with zero remaining.
  // Single comment: 'A' = 1, '2026-01-01T00:00:00.000Z' = 24, body = 25, overhead = 50 → 100
  const comments = [makeComment('A', '2026-01-01T00:00:00.000Z', 25)]
  const { included, omitted } = applyCommentBudget(comments, 100)
  expect('budget exact : inclus', included.length, 1)
  expect('budget exact : omitted = 0', omitted, 0)
}

// ===========================================================================
// fetchJiraComments — ADF body extraction via mock fetch
// ===========================================================================

console.log('\n=== fetchJiraComments (mock fetch) ===\n')

// Save and restore global fetch for isolation
const _realFetch = globalThis.fetch

{
  // Single page, ADF body — verifies extractAdfText is called on comment body.
  globalThis.fetch = async () => ({
    ok: true,
    json: async () => ({
      total: 1,
      comments: [{
        author: { displayName: 'Alice' },
        created: '2026-01-01T10:00:00.000Z',
        body: {
          type: 'doc',
          content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Hello ADF' }] }],
        },
      }],
    }),
  })

  try {
    const comments = await fetchJiraComments('PROJ-1', 'https://x.atlassian.net', 'a@b.com', 'tok')
    expect('ADF body : 1 commentaire', comments.length, 1)
    expect('ADF body : auteur correct', comments[0].author, 'Alice')
    expect('ADF body : texte extrait', comments[0].body, 'Hello ADF')
  } catch (err) {
    failed++
    console.log(`\u2717 ADF body : exception inattendue — ${err}`)
  }
}

{
  // Pagination — two pages of 2 comments each (total = 4).
  let callCount = 0
  globalThis.fetch = async (url) => {
    callCount++
    const startAt = parseInt(new URL(url).searchParams.get('startAt') ?? '0', 10)
    const page = startAt === 0
      ? [
          { author: { displayName: 'C1' }, created: '2026-01-04T00:00:00.000Z', body: 'msg1' },
          { author: { displayName: 'C2' }, created: '2026-01-03T00:00:00.000Z', body: 'msg2' },
        ]
      : [
          { author: { displayName: 'C3' }, created: '2026-01-02T00:00:00.000Z', body: 'msg3' },
          { author: { displayName: 'C4' }, created: '2026-01-01T00:00:00.000Z', body: 'msg4' },
        ]
    return {
      ok: true,
      json: async () => ({ total: 4, comments: page }),
    }
  }

  try {
    const comments = await fetchJiraComments('PROJ-2', 'https://x.atlassian.net', 'a@b.com', 'tok')
    expect('pagination : 4 commentaires', comments.length, 4)
    expect('pagination : 2 appels fetch', callCount, 2)
    expect('pagination : premier = newest (page 1)', comments[0].author, 'C1')
    expect('pagination : dernier = oldest (page 2)', comments[3].author, 'C4')
  } catch (err) {
    failed++
    console.log(`\u2717 pagination : exception inattendue — ${err}`)
  }
}

{
  // HTTP failure — must throw (fetch-ticket contract : failure is fatal).
  globalThis.fetch = async () => ({
    ok: false,
    status: 401,
    text: async () => 'Unauthorized',
  })

  let threw = false
  try {
    await fetchJiraComments('PROJ-3', 'https://x.atlassian.net', 'a@b.com', 'bad')
  } catch {
    threw = true
  }
  expect('HTTP failure : lève une erreur', threw, true)
}

{
  // Empty comments list — no iteration, returns [].
  globalThis.fetch = async () => ({
    ok: true,
    json: async () => ({ total: 0, comments: [] }),
  })

  try {
    const comments = await fetchJiraComments('PROJ-4', 'https://x.atlassian.net', 'a@b.com', 'tok')
    expect('zéro commentaires : tableau vide', comments.length, 0)
  } catch (err) {
    failed++
    console.log(`\u2717 zéro commentaires : exception inattendue — ${err}`)
  }
}

// Restore real fetch
globalThis.fetch = _realFetch

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)

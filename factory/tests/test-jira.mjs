/**
 * Tests unitaires pour factory/lib/jira.mjs.
 *
 * Couvre les deux fonctions pures : extractTicketId et extractAdfText.
 * fetchJiraTicket n'est pas testée : elle fait un appel réseau réel.
 *
 * Usage : node factory/tests/test-jira.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import { extractTicketId, extractAdfText } from '../lib/jira.mjs'

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

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)

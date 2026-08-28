import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parseJsonl, reconstructPhases } from '../dashboard/server.mjs'

const html = readFileSync(new URL('../dashboard/index.html', import.meta.url), 'utf8')
// Extract the Gantt block from the inline <script>.
// Includes: laneOf, renderGantt, laneSubtitle, timelineBounds, buildGlobalTimeline,
//           layoutLaneBars, renderBar — but NOT collectFlags (stubbed by the test).
// Functions are indented with 6 spaces inside the HTML <script> tag.
const laneOfStart = html.indexOf('      function laneOf(')
const collectFlagsStart = html.indexOf('\n      function collectFlags(', laneOfStart)
const source = html.slice(laneOfStart, collectFlagsStart).trim()
const documentStub = { getElementById: () => ({ innerHTML: '' }) }
const timeline = new Function('document', `
  let selectedPhase = null
  const collectFlags = () => []
  const emptySuccess = () => false
  const esc = (value) => String(value)
  const fmtDur = (value) => value + 'ms'
  const truncate = (value) => String(value)
  ${source}
  return { buildGlobalTimeline, layoutLaneBars, renderGantt }
`)(documentStub)

const phases = reconstructPhases(parseJsonl(new URL('../runs/20260820T132311Z-5b34.jsonl', import.meta.url)))
const byName = Object.fromEntries(phases.map((phase) => [phase.name, phase]))
const run = { startedAt: '2026-08-20T13:23:11.133Z', durationMs: 352800, status: 'pass', phases }
const global = timeline.buildGlobalTimeline(run, phases, 0)
const ordered = ['fetch-ticket', 'preflight', 'analyse-1', 'plan-gate-1', 'edit-1-1']

for (let index = 1; index < ordered.length; index++) {
  assert.ok(global.positions.get(ordered[index]) > global.positions.get(ordered[index - 1]))
}
assert.ok(global.positions.get('preflight') - global.positions.get('fetch-ticket') >= 2.5)
assert.ok(global.positions.get('analyse-1') - global.positions.get('preflight') >= 2.5)

const code = timeline.layoutLaneBars([byName['fetch-ticket'], byName.preflight, byName['plan-gate-1']], global, run)
const analyst = timeline.layoutLaneBars([byName['analyse-1']], global, run)
const editor = timeline.layoutLaneBars([byName['edit-1-1']], global, run)
assert.equal(code.items[0].left, global.positions.get('fetch-ticket'))
assert.equal(analyst.items[0].left, global.positions.get('analyse-1'))
assert.equal(editor.items[0].left, global.positions.get('edit-1-1'))

const markup = timeline.renderGantt(run)
for (const name of ['fetch-ticket', 'preflight', 'plan-gate-1']) {
  assert.match(markup, new RegExp(`<button[^>]*class="[^"]*compact[^"]*"[^>]*>[\\s\\S]*?<span class="bar-name">${name}</span>[\\s\\S]*?</button>`))
}
assert.match(markup, /onclick="selectPhase\('fetch-ticket'\)"/)
assert.doesNotMatch(markup, /marker-label/)
assert.doesNotMatch(html, /\.marker-label/)

// Desktop fills the available panel width; only the narrow breakpoint retains
// a compact canvas and horizontal scrolling as a deliberate fallback.
assert.match(html, /\.gantt-inner\s*\{\s*width:\s*100%;\s*min-width:\s*0;/)
assert.match(html, /grid-template-columns:\s*minmax\(140px, var\(--label-w\)\) minmax\(0, 1fr\)/)
assert.doesNotMatch(html, /\.gantt-inner\s*\{\s*min-width:\s*640px/)
assert.match(html, /@media \(max-width: 680px\)[\s\S]*?\.gantt-inner\s*\{\s*min-width:\s*590px/)
assert.ok(!/\.gantt-inner\s*\{[\s\S]*?min-width:\s*640px/.test(html))

// The page shell shares the viewport width with the summary, timeline and
// detail panel. It has fluid gutters but no desktop width cap.
assert.match(html, /\.main\s*\{\s*width:\s*100%;\s*margin:\s*0;\s*padding:\s*clamp\(18px, 3vw, 32px\);\s*\}/)
assert.doesNotMatch(html, /\.main\s*\{[^}]*max-width\s*:/)

console.log('dashboard chronology: ok')

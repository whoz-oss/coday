import assert from 'node:assert/strict'
import { halfOpenIntervalEnvelope, halfOpenIntervalUnionDuration, mergeHalfOpenIntervals } from '../lib/interval-aggregation.mjs'

const input = [{ start: 5, end: 7 }, { start: 1, end: 3 }, { start: 3, end: 5 }, { start: 6, end: 9 }]
assert.deepEqual(mergeHalfOpenIntervals(input), [{ start: 1, end: 9 }])
assert.deepEqual(input, [{ start: 5, end: 7 }, { start: 1, end: 3 }, { start: 3, end: 5 }, { start: 6, end: 9 }])
assert.equal(halfOpenIntervalUnionDuration([{ start: 0, end: 2 }, { start: 1, end: 4 }, { start: 8, end: 9 }]), 5)
assert.deepEqual(halfOpenIntervalEnvelope([{ start: 8, end: 9 }, { start: 1, end: 4 }]), { start: 1, end: 9 })
assert.equal(halfOpenIntervalEnvelope([]), null)
assert.throws(() => mergeHalfOpenIntervals([{ start: 1, end: 1 }]), TypeError)
assert.throws(() => mergeHalfOpenIntervals([{ start: Number.NaN, end: 2 }]), TypeError)

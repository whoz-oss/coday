function validateInterval(interval, index) {
  if (!interval || typeof interval !== 'object' || Array.isArray(interval) || Object.keys(interval).some((key) => !['start', 'end'].includes(key)) || !Number.isFinite(interval.start) || !Number.isFinite(interval.end) || interval.start >= interval.end) throw new TypeError(`Invalid half-open interval at index ${index}`)
  return { start: interval.start, end: interval.end }
}

export function mergeHalfOpenIntervals(intervals) {
  if (!Array.isArray(intervals)) throw new TypeError('Intervals must be an array')
  const sorted = intervals.map(validateInterval).sort((left, right) => left.start - right.start || left.end - right.end)
  const merged = []
  for (const interval of sorted) {
    const prior = merged.at(-1)
    if (!prior || interval.start > prior.end) merged.push({ ...interval })
    else if (interval.end > prior.end) prior.end = interval.end
  }
  return merged
}

export function halfOpenIntervalUnionDuration(intervals) {
  return mergeHalfOpenIntervals(intervals).reduce((duration, interval) => duration + interval.end - interval.start, 0)
}

export function halfOpenIntervalEnvelope(intervals) {
  const merged = mergeHalfOpenIntervals(intervals)
  if (merged.length === 0) return null
  return { start: merged[0].start, end: merged.at(-1).end }
}

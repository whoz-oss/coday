import { Case, CaseStatusEnum } from '@whoz-oss/agentos-api-client'

/**
 * CaseNode — recursive tree node built client-side from a flat Case list.
 *
 * Root nodes have no parentCaseId. Children are sorted by creation date (newest first).
 * The tree is built once in CaseShellComponent from the flat API response.
 */
export interface CaseNode {
  id: string
  title: string
  status: CaseStatusEnum
  created?: string
  children: CaseNode[]
}

/**
 * Build a CaseNode tree from a flat list of Case objects.
 *
 * Cases whose parentCaseId is not found in the list are treated as roots
 * (orphan promotion, same logic as thread-selector).
 *
 * @param cases - flat list from the API
 * @param namespaceId - used to detect root cases (parentCaseId === namespaceId or absent)
 */
export function buildCaseTree(cases: Case[], namespaceId: string): CaseNode[] {
  const allIds = new Set(cases.map((c) => c.id ?? ''))

  // Root cases: no parentCaseId, or parentCaseId equals the namespaceId (API convention),
  // or parentCaseId points to an unknown id (orphan promotion)
  const rootCases = cases.filter(
    (c) => !c.parentCaseId || c.parentCaseId === namespaceId || !allIds.has(c.parentCaseId)
  )
  const childCases = cases.filter(
    (c) => !!c.parentCaseId && c.parentCaseId !== namespaceId && allIds.has(c.parentCaseId)
  )

  // Map parentId → children
  const childMap = new Map<string, Case[]>()
  for (const c of childCases) {
    const parentId = c.parentCaseId as string
    if (!childMap.has(parentId)) childMap.set(parentId, [])
    childMap.get(parentId)!.push(c)
  }

  const toNode = (c: Case): CaseNode => ({
    id: c.id ?? '',
    title: c.title ?? c.id ?? '—',
    status: c.status,
    created: c.created,
    children: (childMap.get(c.id ?? '') ?? []).sort(byCreatedDesc).map(toNode),
  })

  return rootCases.sort(byCreatedDesc).map(toNode)
}

function byCreatedDesc(a: Case, b: Case): number {
  return (b.created ?? '') > (a.created ?? '') ? 1 : -1
}

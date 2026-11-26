import { MemberItem } from '@whoz-oss/agentos-api-client'
import { memberLabel } from '../namespace-members/namespace-members.util'

/** A person of the case who has it open right now, for the avatars of its header. */
export interface PresentMember {
  id: string
  /** Best name for the person: "First Last", else the email. */
  label: string
  email: string
  initials: string
  /** Hue (0-359) of the colour that tells this person from the others, stable for a given user id. */
  hue: number
}

/** A stable hue for a user id, so that a person keeps their colour from one visit and one tab to the next. */
export function hueOf(id: string): number {
  let hash = 0
  for (const char of id.toLowerCase()) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  return hash % 360
}

/** Letters of the first and last word of a label, or of its first two letters for a single word, upper case. */
export function initialsOf(label: string): string {
  const words = label
    .replace(/@.*$/, '')
    .split(/[\s._-]+/)
    .filter(Boolean)
  const letters = words.length > 1 ? `${words[0]![0]}${words[words.length - 1]![0]}` : (words[0] ?? '').slice(0, 2)
  return letters.toUpperCase()
}

/**
 * The avatar URL of each of [people] by user id, as [resolve] gives it for their email (Gravatar in the app). A person
 * without an email, without an avatar URL, or whose URL cannot be computed is left out: the initials show instead.
 */
export async function avatarUrlsOf(
  people: readonly Pick<PresentMember, 'id' | 'email'>[],
  resolve: (email: string) => Promise<string | null>
): Promise<Map<string, string>> {
  const entries = await Promise.all(
    people.map(async (person) => {
      try {
        return [person.id, person.email ? await resolve(person.email) : null] as const
      } catch {
        return [person.id, null] as const
      }
    })
  )
  return new Map(entries.filter((entry): entry is readonly [string, string] => !!entry[1]))
}

/**
 * The members of [directory] whose id is in [presentIds], in the order of the directory, without the current user:
 * the viewer knows they are there. Ids are compared regardless of the UUID letter case.
 * A present id that is not a member of the case is left out: the case members are the only people shown.
 */
export function presentMembers(
  directory: readonly MemberItem[],
  presentIds: readonly string[],
  currentUserId: string | null | undefined
): PresentMember[] {
  const present = new Set(presentIds.map((id) => id.toLowerCase()))
  const current = currentUserId?.toLowerCase()
  const people = directory
    .filter((member) => present.has(member.id.toLowerCase()) && member.id.toLowerCase() !== current)
    .map((member) => {
      const label = memberLabel(member) || member.id
      return {
        id: member.id,
        label,
        email: member.email ?? '',
        initials: initialsOf(label) || '?',
        hue: hueOf(member.id),
      }
    })
  return people
}

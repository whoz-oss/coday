import { MemberItem } from '@whoz-oss/agentos-api-client'
import { memberLabel } from '../namespace-members/namespace-members.util'

/**
 * A person of a shared case that the composer can mention with `@`. Mentioning someone is plain text for the
 * conversation: it never calls an agent.
 */
export interface MemberMention {
  /** User id of the member. */
  id: string
  /** What is typed after the `@`: no whitespace and no `@`, so that it fits the mention syntax. */
  handle: string
  /** Best name for the member: "First Last", else the email. */
  label: string
  email: string
}

/** At most this many people are suggested at once, so that the agents stay in sight. */
const MAX_SUGGESTIONS = 8

/** The email before its `@`, else the label without its spaces, else the id. */
export function memberHandle(
  member: Pick<MemberItem, 'id' | 'email' | 'firstname' | 'lastname' | 'externalId'>
): string {
  const fromEmail = member.email?.split('@')[0] ?? ''
  const raw = fromEmail || memberLabel(member)
  return raw.replace(/[\s@]+/g, '') || member.id
}

/** The people of the case who can be mentioned: everyone but the current user. */
export function memberMentions(
  members: readonly MemberItem[],
  currentUserId: string | null | undefined
): MemberMention[] {
  return members
    .filter((member) => !currentUserId || member.id.toLowerCase() !== currentUserId.toLowerCase())
    .map((member) => ({
      id: member.id,
      handle: memberHandle(member),
      label: memberLabel(member) || member.id,
      email: member.email ?? '',
    }))
}

/** The people matching what is typed after the `@`: those whose handle, name or email starts with it come first. */
export function filterMemberMentions(members: readonly MemberMention[], prefix: string): MemberMention[] {
  const wanted = prefix.toLowerCase()
  const fields = (member: MemberMention) => [member.handle, member.label, member.email].map((f) => f.toLowerCase())
  const starting = members.filter((member) => fields(member).some((field) => field.startsWith(wanted)))
  const containing = members.filter(
    (member) => !starting.includes(member) && fields(member).some((field) => field.includes(wanted))
  )
  return [...starting, ...containing].slice(0, MAX_SUGGESTIONS)
}

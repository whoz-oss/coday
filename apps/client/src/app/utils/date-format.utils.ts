import { formatDistanceToNow, format, isYesterday, differenceInDays } from 'date-fns'

/**
 * Format a date with relative time and precise time (HH:MM:SS)
 *
 * Examples:
 * - "just now (14:30:45)"
 * - "5 min ago (14:25:12)"
 * - "3h ago (11:30:45)"
 * - "yesterday 09:15:30"
 * - "2d ago 08:00:00"
 * - "12/01/2024 15:30:00"
 *
 * @param date - The date to format
 * @returns Formatted date string with relative time and precise time
 */
export function formatDateWithTime(date: Date): string {
  // Check if date is valid
  if (!date || isNaN(date.getTime())) {
    return 'unknown'
  }

  const now = new Date()
  const timeStr = format(date, 'HH:mm:ss')

  // Just now (less than 1 minute)
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / (1000 * 60))
  if (diffMins < 1) {
    return `just now (${timeStr})`
  }

  // Less than 1 hour: "X min ago"
  if (diffMins < 60) {
    return `${diffMins} min ago (${timeStr})`
  }

  // Less than 24 hours: "Xh ago"
  const diffHours = Math.floor(diffMs / (1000 * 60 * 60))
  if (diffHours < 24) {
    return `${diffHours}h ago (${timeStr})`
  }

  // Yesterday
  if (isYesterday(date)) {
    return `yesterday ${timeStr}`
  }

  // Less than 7 days: "Xd ago"
  const daysDiff = differenceInDays(now, date)
  if (daysDiff < 7) {
    return `${daysDiff}d ago ${timeStr}`
  }

  // Older dates: full date + time
  return `${format(date, 'dd/MM/yyyy')} ${timeStr}`
}

/**
 * Format a date with relative time only (no precise time)
 *
 * Examples:
 * - "just now"
 * - "5 minutes ago"
 * - "3 hours ago"
 * - "yesterday"
 * - "2 days ago"
 * - "12/01/2024"
 *
 * @param date - The date to format
 * @returns Formatted date string with relative time
 */
export function formatRelativeDate(date: Date): string {
  // Check if date is valid
  if (!date || isNaN(date.getTime())) {
    return 'unknown'
  }

  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / (1000 * 60))

  // Just now (less than 1 minute)
  if (diffMins < 1) {
    return 'just now'
  }

  // Less than 24 hours: use formatDistanceToNow
  const diffHours = Math.floor(diffMs / (1000 * 60 * 60))
  if (diffHours < 24) {
    return formatDistanceToNow(date, { addSuffix: true })
  }

  // Yesterday
  if (isYesterday(date)) {
    return 'yesterday'
  }

  // Less than 7 days: "X days ago"
  const daysDiff = differenceInDays(now, date)
  if (daysDiff < 7) {
    return `${daysDiff} days ago`
  }

  // Older dates: full date
  return format(date, 'dd/MM/yyyy')
}

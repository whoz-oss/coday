import { Pipe, PipeTransform, inject } from '@angular/core'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'

/**
 * Pipe to highlight matching text in a string
 * Usage: {{ text | highlight:searchTerm }}
 */
@Pipe({
  name: 'highlight',
  standalone: true,
})
export class HighlightPipe implements PipeTransform {
  private readonly sanitizer = inject(DomSanitizer)

  transform(value: string, searchTerm: string): SafeHtml {
    if (!searchTerm || !value) {
      return value
    }

    // Escape special regex characters in search term
    const escapedTerm = searchTerm.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

    // Create regex for case-insensitive global search
    const regex = new RegExp(`(${escapedTerm})`, 'gi')

    // Replace matches with bold wrapped text
    const highlighted = value.replace(regex, '<strong>$1</strong>')

    // Return sanitized HTML
    return this.sanitizer.bypassSecurityTrustHtml(highlighted)
  }
}

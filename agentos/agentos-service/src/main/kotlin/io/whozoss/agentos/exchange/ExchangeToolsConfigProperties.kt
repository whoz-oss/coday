package io.whozoss.agentos.exchange

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level policy for the file-plugin tools granted on the exchange scopes: which agents get
 * them at all, and how those tools behave.
 *
 * Bound from the `agentos.exchange.tools` prefix in application.yml.
 *
 * The SDK and the plugins carry no Spring dependency — a [io.whozoss.agentos.sdk.tool.ToolPlugin]
 * receives its whole configuration as the `JsonNode` handed to `provideTools`. This class is
 * therefore the service-side home of every knob the file-plugin reads, and [ExchangeToolGrantService]
 * is what turns it into that node. A plugin jar predating one of these keys simply ignores it and
 * falls back to its own compiled default.
 *
 * One single set of values is shared by the case and the namespace scope: an agent sees the same
 * limits wherever a file lives, and an operator gets one knob per concern rather than two. The
 * plugin's three remaining keys are deliberately absent here — `rootPath` and `readOnly` are
 * computed per run (scope root, invoking user's Namespace WRITE right), and `readMaxSizeMb` derives
 * from `agentos.exchange.read-max-size-bytes` so the agent read cap can never drift from the one the
 * REST read/download path enforces.
 *
 * The tuning defaults below duplicate the file-plugin's own constants (`ImageProcessor`,
 * `ReadDocumentTool`): the plugin is a separate, Spring-free module whose classes are not on the
 * service classpath, so they cannot be imported. Keep the two in sync when the plugin moves.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_EXCHANGE_TOOLS_CASE_ENABLED_BY_DEFAULT
 * - AGENTOS_EXCHANGE_TOOLS_NAMESPACE_ENABLED_BY_DEFAULT
 * - AGENTOS_EXCHANGE_TOOLS_EXTRA_DENY_PATTERNS (comma-separated)
 * - AGENTOS_EXCHANGE_TOOLS_IMAGE_MAX_DIMENSION
 * - AGENTOS_EXCHANGE_TOOLS_IMAGE_JPEG_QUALITY
 * - AGENTOS_EXCHANGE_TOOLS_IMAGE_MAX_SOURCE_PIXELS
 * - AGENTOS_EXCHANGE_TOOLS_IMAGE_PASS_THROUGH_MAX_BYTES
 * - AGENTOS_EXCHANGE_TOOLS_DOCUMENT_MAX_OUTPUT_CHARS
 * - AGENTOS_EXCHANGE_TOOLS_DOCUMENT_MAX_ATTACHED_IMAGES
 * - AGENTOS_EXCHANGE_TOOLS_DOCUMENT_MAX_TABLE_COLUMNS
 * - AGENTOS_EXCHANGE_TOOLS_DOCUMENT_MAX_CELL_CHARS
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   exchange:
 *     tools:
 *       case-enabled-by-default: true
 *       image-max-dimension: 1568
 *       extra-deny-patterns: "*.bak,*confidential*"
 * ```
 */
@ConfigurationProperties(prefix = "agentos.exchange.tools")
data class ExchangeToolsConfigProperties(
    /**
     * Grants the case exchange to every agent that does not mention
     * [ExchangeIntegrationTypes.CASE] in its `integrations` map.
     *
     * Off by default so switching it on stays an explicit operator decision: a default grant
     * materialises a case exchange directory for every case a granted agent runs in, and widens the
     * tool set every agent advertises to its LLM. An agent that declares the key always wins,
     * including with an empty list — the explicit opt-out.
     */
    val caseEnabledByDefault: Boolean = false,
    /**
     * Same platform default for [ExchangeIntegrationTypes.NAMESPACE]. Every namespace grant, this
     * default included, additionally requires the invoking user to hold Namespace READ; a run
     * without an identified user is denied (fail-closed). Write access follows the user's
     * Namespace WRITE right.
     */
    val namespaceEnabledByDefault: Boolean = false,
    /**
     * Patterns blocked on top of the plugin's built-in sensitive-file list (`.env`, `*.key`,
     * `*.pem`, ...). Additive only: an instance can harden the deny-list for its own naming
     * conventions, it can never weaken the built-in one.
     *
     * A pattern matches the final path segment only (the file or directory name), never the full
     * relative path, with the plugin's simple matcher (`matchesPattern`): `*suffix`, `prefix*`,
     * `*contains*` or an exact name. A pattern containing a slash therefore matches nothing, and a
     * directory-scoped intent does not carry: `internal-*` denies the directory entry
     * `internal-reports` while a read of the file `summary.md` inside it still passes, because only
     * that leaf name is tested. To fence off content, use name patterns that hold at every depth,
     * like `*.bak` or `*confidential*`.
     */
    val extraDenyPatterns: List<String> = emptyList(),
    /**
     * Longest-edge size, in pixels, of the images `readAsImage` and `readDocument` hand to the LLM;
     * larger ones are downscaled. The right value tracks the vision model in use, which is why it
     * belongs to the instance rather than to a constant.
     */
    val imageMaxDimension: Int = 1024,
    /**
     * JPEG re-encoding quality, in `[0, 1]`, for those same two tools: prompt bytes traded against
     * the legibility of screenshots and scans. [ExchangeToolGrantService] clamps an out-of-range
     * value — the JPEG writer would otherwise throw in the middle of a tool call, far from the
     * misconfiguration that caused it.
     */
    val imageJpegQuality: Float = 0.80f,
    /**
     * Decode-bomb guard: any source or embedded image above this pixel count is refused before being
     * decoded. Raise it only on an instance whose heap can absorb the larger raster.
     */
    val imageMaxSourcePixels: Long = 50_000_000L,
    /**
     * Originals at or below this byte size that already fit [imageMaxDimension] are forwarded
     * untouched instead of re-encoded, which preserves text sharpness in screenshots and diagrams.
     */
    val imagePassThroughMaxBytes: Long = 1L * 1024 * 1024,
    /**
     * Markdown character budget of a single `readDocument` call; a longer document is paged via
     * `startElement`. The main lever on how much of a large `.docx` fits in one agent context, so it
     * scales with the context window of the models the instance runs.
     */
    val documentMaxOutputChars: Int = 100_000,
    /**
     * Maximum embedded pictures `readDocument` attaches per call — the image half of that same
     * budget, bounding the token blow-up of an image-heavy document.
     */
    val documentMaxAttachedImages: Int = 10,
    /**
     * Columns beyond this are dropped when `readDocument` renders a `.docx` table to Markdown, so one
     * wide table cannot eat the whole output budget.
     */
    val documentMaxTableColumns: Int = 64,
    /**
     * A single table cell longer than this is truncated by `readDocument`, so one verbose cell cannot
     * dominate the rendered table.
     */
    val documentMaxCellChars: Int = 5000,
)

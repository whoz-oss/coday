import * as path from 'path'
import { readFileSync, existsSync } from 'node:fs'
import {
  AssistantToolFactory,
  CodayTool,
  CommandContext,
  FunctionTool,
  IntegrationConfig,
  Interactor,
} from '@coday/model'
import * as XLSX from 'xlsx'

/**
 * ExcelTools: Read Excel files (.xls, .xlsx) and expose their content to agents.
 *
 * Tools provided:
 * - EXCEL__listSheets   — list all sheet names in a workbook
 * - EXCEL__readSheet    — read a sheet as JSON rows (with optional row limit)
 * - EXCEL__getSheetInfo — get sheet dimensions and column headers without loading all data
 *
 * File paths follow the same convention as FileTools:
 * - "project://path/to/file.xlsx" for project files
 * - "exchange://file.xlsx" for files uploaded in the current conversation
 *
 * Large file strategy: use getSheetInfo first to understand the structure,
 * then readSheet with maxRows to load data incrementally.
 */
export class ExcelTools extends AssistantToolFactory {
  static readonly TYPE = 'EXCEL' as const

  constructor(interactor: Interactor, instanceName: string, config: IntegrationConfig) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    const resolvePath = (filePath: string): string => {
      if (filePath.startsWith('project://')) {
        return path.resolve(context.project.root, filePath.replace('project://', ''))
      }
      if (filePath.startsWith('exchange://')) {
        if (!context.threadFilesRoot) {
          throw new Error('No exchange workspace available in this context')
        }
        return path.resolve(context.threadFilesRoot, filePath.replace('exchange://', ''))
      }
      throw new Error(`File path must start with "project://" or "exchange://". Got: "${filePath}"`)
    }

    const loadWorkbook = (filePath: string): XLSX.WorkBook => {
      const absolutePath = resolvePath(filePath)
      if (!existsSync(absolutePath)) {
        throw new Error(`File not found: ${filePath}`)
      }
      const buffer = readFileSync(absolutePath)
      return XLSX.read(buffer, { type: 'buffer' })
    }

    // --- listSheets ---
    const listSheetsFunction: FunctionTool<{ filePath: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__listSheets`,
        description:
          'List all sheet names in an Excel file (.xls or .xlsx). ' +
          'Use this first to discover the structure of a workbook before reading data. ' +
          'File path must start with "project://" or "exchange://".',
        parameters: {
          type: 'object',
          properties: {
            filePath: {
              type: 'string',
              description: 'Path to the Excel file (e.g. "exchange://report.xlsx" or "project://data/sales.xls")',
            },
          },
        },
        parse: JSON.parse,
        function: ({ filePath }: { filePath: string }): string => {
          try {
            const workbook = loadWorkbook(filePath)
            return JSON.stringify({ sheets: workbook.SheetNames }, null, 2)
          } catch (error) {
            return `Error: ${error}`
          }
        },
      },
    }
    result.push(listSheetsFunction)

    // --- readSheet ---
    const readSheetFunction: FunctionTool<{
      filePath: string
      sheetName?: string
      maxRows?: number
      startRow?: number
    }> = {
      type: 'function',
      function: {
        name: `${this.name}__readSheet`,
        description:
          'Read the content of a sheet from an Excel file as JSON rows. ' +
          'If sheetName is omitted, the first sheet is used. ' +
          'Use maxRows to limit the number of rows returned (recommended for large files). ' +
          'Use startRow to paginate through large datasets (0-indexed, after the header row). ' +
          'Returns an array of objects where keys are column headers. ' +
          'File path must start with "project://" or "exchange://".',
        parameters: {
          type: 'object',
          properties: {
            filePath: {
              type: 'string',
              description: 'Path to the Excel file (e.g. "exchange://report.xlsx")',
            },
            sheetName: {
              type: 'string',
              description: 'Name of the sheet to read. Defaults to the first sheet if omitted.',
            },
            maxRows: {
              type: 'number',
              description:
                'Maximum number of data rows to return (default: 100, max: 1000). Use to avoid overloading context.',
            },
            startRow: {
              type: 'number',
              description: 'Zero-indexed row offset (after header) to start reading from. Use for pagination.',
            },
          },
        },
        parse: JSON.parse,
        function: ({
          filePath,
          sheetName,
          maxRows = 100,
          startRow = 0,
        }: {
          filePath: string
          sheetName?: string
          maxRows?: number
          startRow?: number
        }): string => {
          try {
            const workbook = loadWorkbook(filePath)
            const targetSheet = sheetName ?? workbook.SheetNames[0]

            if (!targetSheet) {
              return 'Error: workbook has no sheets'
            }
            if (!workbook.SheetNames.includes(targetSheet)) {
              return `Error: sheet "${targetSheet}" not found. Available sheets: ${workbook.SheetNames.join(', ')}`
            }

            const sheet = workbook.Sheets[targetSheet]
            if (!sheet) {
              return `Error: could not load sheet "${targetSheet}"`
            }

            // Convert to array of arrays to support pagination
            const allRows = XLSX.utils.sheet_to_json<Record<string, unknown>>(sheet, {
              defval: null,
              raw: false, // format dates and numbers as strings for LLM readability
            })

            const cappedMax = Math.min(maxRows, 1000)
            const sliced = allRows.slice(startRow, startRow + cappedMax)
            const totalRows = allRows.length
            const hasMore = startRow + cappedMax < totalRows

            return JSON.stringify(
              {
                sheet: targetSheet,
                totalRows,
                returnedRows: sliced.length,
                startRow,
                hasMore,
                ...(hasMore ? { nextStartRow: startRow + cappedMax } : {}),
                rows: sliced,
              },
              null,
              2
            )
          } catch (error) {
            return `Error: ${error}`
          }
        },
      },
    }
    result.push(readSheetFunction)

    // --- getSheetInfo ---
    const getSheetInfoFunction: FunctionTool<{ filePath: string; sheetName?: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__getSheetInfo`,
        description:
          'Get metadata about a sheet without loading all its data: ' +
          'number of rows, column headers, and a preview of the first 3 rows. ' +
          'Use this to understand the structure of a large sheet before deciding how to read it. ' +
          'File path must start with "project://" or "exchange://".',
        parameters: {
          type: 'object',
          properties: {
            filePath: {
              type: 'string',
              description: 'Path to the Excel file',
            },
            sheetName: {
              type: 'string',
              description: 'Name of the sheet. Defaults to the first sheet if omitted.',
            },
          },
        },
        parse: JSON.parse,
        function: ({ filePath, sheetName }: { filePath: string; sheetName?: string }): string => {
          try {
            const workbook = loadWorkbook(filePath)
            const targetSheet = sheetName ?? workbook.SheetNames[0]

            if (!targetSheet) {
              return 'Error: workbook has no sheets'
            }
            if (!workbook.SheetNames.includes(targetSheet)) {
              return `Error: sheet "${targetSheet}" not found. Available sheets: ${workbook.SheetNames.join(', ')}`
            }

            const sheet = workbook.Sheets[targetSheet]
            if (!sheet) {
              return `Error: could not load sheet "${targetSheet}"`
            }

            const allRows = XLSX.utils.sheet_to_json<Record<string, unknown>>(sheet, {
              defval: null,
              raw: false,
            })

            const columns = allRows.length > 0 ? Object.keys(allRows[0] ?? {}) : []
            const preview = allRows.slice(0, 3)

            return JSON.stringify(
              {
                sheet: targetSheet,
                totalRows: allRows.length,
                columnCount: columns.length,
                columns,
                preview,
              },
              null,
              2
            )
          } catch (error) {
            return `Error: ${error}`
          }
        },
      },
    }
    result.push(getSheetInfoFunction)

    return result
  }

  override async kill(): Promise<void> {
    // No persistent resources to clean up
  }
}

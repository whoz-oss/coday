import * as path from 'path'
import { existsSync } from 'node:fs'
import {
  AssistantToolFactory,
  CodayTool,
  CommandContext,
  FunctionTool,
  IntegrationConfig,
  Interactor,
} from '@coday/model'
import ExcelJS from 'exceljs'

/**
 * ExcelTools: Read Excel files (.xlsx) and expose their content to agents.
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

    const loadWorkbook = async (filePath: string): Promise<ExcelJS.Workbook> => {
      const absolutePath = resolvePath(filePath)
      if (!existsSync(absolutePath)) {
        throw new Error(`File not found: ${filePath}`)
      }
      const workbook = new ExcelJS.Workbook()
      await workbook.xlsx.readFile(absolutePath)
      return workbook
    }

    // --- listSheets ---
    const listSheetsFunction: FunctionTool<{ filePath: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__listSheets`,
        description:
          'List all sheet names in an Excel file (.xlsx). ' +
          'Use this first to discover the structure of a workbook before reading data. ' +
          'File path must start with "project://" or "exchange://".',
        parameters: {
          type: 'object',
          properties: {
            filePath: {
              type: 'string',
              description: 'Path to the Excel file (e.g. "exchange://report.xlsx" or "project://data/sales.xlsx")',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ filePath }: { filePath: string }): Promise<string> => {
          try {
            const workbook = await loadWorkbook(filePath)
            const sheets = workbook.worksheets.map((ws) => ws.name)
            return JSON.stringify({ sheets }, null, 2)
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
        function: async ({
          filePath,
          sheetName,
          maxRows = 100,
          startRow = 0,
        }: {
          filePath: string
          sheetName?: string
          maxRows?: number
          startRow?: number
        }): Promise<string> => {
          try {
            const workbook = await loadWorkbook(filePath)
            const sheet = sheetName ? workbook.getWorksheet(sheetName) : workbook.worksheets[0]

            if (!sheet) {
              const available = workbook.worksheets.map((ws) => ws.name).join(', ')
              return sheetName
                ? `Error: sheet "${sheetName}" not found. Available sheets: ${available}`
                : 'Error: workbook has no sheets'
            }

            // Extract header row (first row)
            const headerRow = sheet.getRow(1)
            const headers: string[] = []
            headerRow.eachCell({ includeEmpty: true }, (cell, colNumber) => {
              headers[colNumber - 1] = cell.text || `Column${colNumber}`
            })

            // Extract data rows with pagination (data starts at row 2)
            const cappedMax = Math.min(maxRows, 1000)
            const dataStartRow = 2 + startRow
            const dataEndRow = dataStartRow + cappedMax - 1
            const totalDataRows = sheet.rowCount - 1 // exclude header

            const rows: Record<string, unknown>[] = []
            for (let rowIndex = dataStartRow; rowIndex <= Math.min(dataEndRow, sheet.rowCount); rowIndex++) {
              const row = sheet.getRow(rowIndex)
              if (row.hasValues) {
                const rowData: Record<string, unknown> = {}
                row.eachCell({ includeEmpty: true }, (cell, colNumber) => {
                  const header = headers[colNumber - 1] ?? `Column${colNumber}`
                  rowData[header] = cell.text || null
                })
                rows.push(rowData)
              }
            }

            const hasMore = dataStartRow + cappedMax - 1 < sheet.rowCount

            return JSON.stringify(
              {
                sheet: sheet.name,
                totalRows: totalDataRows,
                returnedRows: rows.length,
                startRow,
                hasMore,
                ...(hasMore ? { nextStartRow: startRow + cappedMax } : {}),
                rows,
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
        function: async ({ filePath, sheetName }: { filePath: string; sheetName?: string }): Promise<string> => {
          try {
            const workbook = await loadWorkbook(filePath)
            const sheet = sheetName ? workbook.getWorksheet(sheetName) : workbook.worksheets[0]

            if (!sheet) {
              const available = workbook.worksheets.map((ws) => ws.name).join(', ')
              return sheetName
                ? `Error: sheet "${sheetName}" not found. Available sheets: ${available}`
                : 'Error: workbook has no sheets'
            }

            // Extract headers
            const headerRow = sheet.getRow(1)
            const columns: string[] = []
            headerRow.eachCell({ includeEmpty: true }, (cell, colNumber) => {
              columns[colNumber - 1] = cell.text || `Column${colNumber}`
            })

            // Preview: first 3 data rows
            const preview: Record<string, unknown>[] = []
            for (let rowIndex = 2; rowIndex <= Math.min(4, sheet.rowCount); rowIndex++) {
              const row = sheet.getRow(rowIndex)
              if (row.hasValues) {
                const rowData: Record<string, unknown> = {}
                row.eachCell({ includeEmpty: true }, (cell, colNumber) => {
                  const header = columns[colNumber - 1] ?? `Column${colNumber}`
                  rowData[header] = cell.text || null
                })
                preview.push(rowData)
              }
            }

            return JSON.stringify(
              {
                sheet: sheet.name,
                totalRows: sheet.rowCount - 1,
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

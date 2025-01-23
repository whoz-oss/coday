import {ActiveFieldMapping, ActiveFieldOption} from "./jira-field-mapper";
import {JiraField} from "./jira";

export function generateMappingDescription(mappings: ActiveFieldMapping[]): string {
    // Helper function to determine field type
    const determineFieldType = (field: ActiveFieldMapping): string => {
        if (field.options) {
            const multiSelectThreshold = 0.3;
            const multiSelectRatio = field.options.filter(opt => opt.count > 1).length / field.options.length;

            return multiSelectRatio > multiSelectThreshold ? 'Multi-Select' : 'Single-Select';
        }
        return 'Unknown';
    };

    // Helper function to generate query examples
    const generateQueryExamples = (field: ActiveFieldMapping): string => {
        const fieldId = field.id;
        const options = field.options || [];

        if (options.length === 0) return 'No query examples available';

        const topOptions = options.slice(0, 3).map(opt => opt.value);
        const fieldType = determineFieldType(field);

        if (fieldType === 'Multi-Select') {
            return `
    - Multiple Value Search: cf[${fieldId}] IN ("${topOptions.join('", "')}") 
    - Partial Match: cf[${fieldId}] ~ "${topOptions[0]}"
    `;
        } else {
            return `
    - Exact Match: cf[${fieldId}] = "${topOptions[0]}"
    - Partial Search: cf[${fieldId}] ~ "${topOptions[0]}"
    `;
        }
    };

    // Generate detailed field descriptions
    const fieldDescriptions = mappings.map(field => {
        const fieldType = determineFieldType(field);

        const optionsSection = field.options
            ? `\n    Available Values: ${field.options.slice(0, 10)
                .map(opt => `"${opt.value}" (${opt.count} occurrences)`)
                .join(', ')}${field.options.length > 10 ? '...' : ''}`
            : '';

        const queryExamples = generateQueryExamples(field);

        return `
    Field Name: ${field.name}
    Jira Field ID: ${field.id}
    Field Type: ${fieldType}
    Searchable: ${field.searchable ? 'Yes' : 'No'}
    ${optionsSection}
    
    Query Examples:
    ${queryExamples}
    `;
    }).join('\n\n');

    return `# Comprehensive Jira Custom Field Mapping

## Overview
Total Mappable Fields: ${mappings.length}

## Detailed Field Descriptions
${fieldDescriptions}

## JQL Query Construction Guidelines
1. Always prefix custom fields with 'cf[FIELD_ID]'
2. Use exact field IDs for precise searching
3. Respect field types when constructing queries
4. Use 'IN' for multi-select fields
5. Use '=' for single-select or exact match
6. Consider field searchability

## Field Type Explanation
- Single-Select: Only one value can be selected
- Multi-Select: Multiple values can be chosen
- Unknown: Unable to determine field type

## Recommendation
Verify field types and adjust queries accordingly based on your specific Jira instance.

Note: Options derived from the most recent ${mappings.length > 0 ? '500' : '0'} tickets.`;
}

export function extractFieldOptionsFromTickets(tickets: any[], activeFields: JiraField[]): ActiveFieldMapping[] {
    const fieldOptionsMap = new Map<string, Map<string, number>>()

    activeFields.forEach(field => {
        fieldOptionsMap.set(field.id, new Map<string, number>())
    })

    const simplifyValue = (value: any, fieldKey: string): string => {
        // Special handling for specific fields
        switch (fieldKey) {
            case 'comment':
                return `${value.total || 0} comment(s)`

            case 'description':
                if (typeof value === 'string') {
                    return value.length > 50 ? value.substring(0, 50) + '...' : value
                }
                return 'Has description'

            default:
                if (typeof value === 'object' && value !== null) {
                    return value.value ||
                        value.name ||
                        value.displayName ||
                        value.key ||
                        value.id ||
                        (JSON.stringify(value).length > 50
                            ? JSON.stringify(value).substring(0, 50) + '...'
                            : JSON.stringify(value))
                }
                return String(value)
        }
    }

    tickets.forEach(ticket => {
        activeFields.forEach(field => {
            const fieldId = field.id
            const fieldKey = field.key || field.id

            const fieldValue = ticket.fields[fieldKey] ||
                ticket.fields[`customfield_${fieldId}`] ||
                ticket.fields[fieldId]

            if (fieldValue) {
                const fieldMap = fieldOptionsMap.get(fieldId) || new Map()

                let values: any[] = Array.isArray(fieldValue)
                    ? fieldValue
                    : [fieldValue]

                values.forEach(value => {
                    const processedValue = simplifyValue(value, fieldKey)


                    if (processedValue) {
                        fieldMap.set(
                            processedValue,
                            (fieldMap.get(processedValue) || 0) + 1
                        )
                    }
                })
            }
        })
    })

    return activeFields.map(field => {
        const optionsMap = fieldOptionsMap.get(field.id) || new Map()
        const options: ActiveFieldOption[] = Array.from(optionsMap.entries())
            .map(([value, count]) => ({
                value,
                count,
            }))
            .sort((a, b) => b.count - a.count)

        return {
            id: field.id,
            name: field.name,
            key: field?.key,
            custom: field.custom,
            searchable: field.searchable,
            options: options.length > 0 ? options : undefined
        }
    }).filter((field) => field.options && field.options.length > 0)
}

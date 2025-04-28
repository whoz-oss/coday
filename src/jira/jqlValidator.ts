/**
 * Interface for field mapping information
 */
export interface FieldMappingInfo {
  jqlResearchDescription: string;
}

/**
 * Simple interface for field operator pair
 */
interface FieldOperatorPair {
  field: string;
  operator: string;
}

/**
 * Validates that JQL query uses correct operators for each field
 * 
 * @param jqlQuery - The JQL query to validate
 * @param fieldMappingInfo - Field mapping information containing allowed operators
 * @returns Whether the JQL query uses valid operators
 */
export function validateJqlOperators(jqlQuery: string, fieldMappingInfo: FieldMappingInfo): boolean {
  // Parse JQL to extract field-operator pairs
  const fieldOperatorPairs = parseJql(jqlQuery);
  
  // Extract operator information from fieldMappingInfo
  const fieldOperatorMap = buildFieldOperatorMap(fieldMappingInfo);
  
  // Validate each field-operator pair
  for (const { field, operator } of fieldOperatorPairs) {
    // Skip validation for logical operators (AND, OR, NOT)
    if (['AND', 'OR', 'NOT'].includes(operator.toUpperCase())) {
      continue;
    }
    
    // Check if field exists in the mapping
    if (!fieldOperatorMap[field]) {
      return false; // Unknown field
    }
    
    // If field has no operators defined, we'll be permissive
    if (!fieldOperatorMap[field] || fieldOperatorMap[field].length === 0) {
      continue;
    }
    
    // Check if operator is valid for this field
    if (!fieldOperatorMap[field].includes(operator)) {
      return false;
    }
  }
  
  return true;
}

/**
 * Parse JQL query to extract field-operator pairs
 * 
 * @param jqlQuery - The JQL query to parse
 * @returns Array of field-operator pairs
 */
function parseJql(jqlQuery: string): FieldOperatorPair[] {
  const result: FieldOperatorPair[] = [];
  
  // Simple regex to extract field and operator
  const regex = /(\w+|\w+\[\d+\])\s+(!?[~=<>]+|(?:not\s+)?in|is(?:\s+not)?|was(?:\s+not)?|changed)/gi;
  let match: RegExpExecArray | null;
  
  while ((match = regex.exec(jqlQuery)) !== null) {
    result.push({
      field: match[1].toLowerCase(),
      operator: match[2].toLowerCase()
    });
  }
  
  return result;
}

/**
 * Build a map of fields to their allowed operators from fieldMappingInfo
 * 
 * @param fieldMappingInfo - Field mapping information
 * @returns Map of fields to their allowed operators
 */
function buildFieldOperatorMap(fieldMappingInfo: FieldMappingInfo): Record<string, string[]> {
  const operatorMap: Record<string, string[]> = {};
  
  // Parse the jqlResearchDescription to extract field and operator information
  const fieldDescriptions = fieldMappingInfo.jqlResearchDescription.split('\n\n');
  
  for (const fieldDesc of fieldDescriptions) {
    // Skip headers and non-field descriptions
    if (!fieldDesc.includes('Query Key:')) {
      continue;
    }
    
    // Extract field name and operators
    const queryKeyMatch = fieldDesc.match(/Query Key: ([^\n]+)/);
    const operatorMatch = fieldDesc.match(/Operator: \s*\[(.*?)\]/);
    
    if (queryKeyMatch) {
      const fieldKey = queryKeyMatch[1].trim().toLowerCase();
      
      // Extract operators if available
      const operators = operatorMatch 
        ? operatorMatch[1].split(',').map(op => op.trim().toLowerCase())
        : [];
      
      operatorMap[fieldKey] = operators;
    }
  }
  
  return operatorMap;
}
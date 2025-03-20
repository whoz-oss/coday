import { createHash } from 'crypto';
import { Interactor } from '../../model';
import { JiraFieldMapper } from './jira-field-mapper';
import { FieldMappingDescription } from './jira';

// Type definition for mapping result
export interface MappingResult {
  mappings: any[];
  autocompleteData: any;
  description: FieldMappingDescription;
}

// TTL constant
const TTL_MS = 8 * 60 * 60 * 1000; // 8 hours

// Single map for all promises with their timestamps
const MAPPING_CACHE = new Map<string, {
  promise: Promise<MappingResult>;
  timestamp: number;
}>();

// Utility function for generating cache keys
function generateCacheKey(jiraBaseUrl: string, jiraApiToken: string, jiraUsername: string): string {
  const hash = createHash('sha256')
    .update(`${jiraBaseUrl}:${jiraUsername}:${jiraApiToken}`)
    .digest('hex');
  
  return hash;
}

export class JiraFieldMappingCache {
  /**
   * Get field mapping for a specific Jira instance.
   * Reuses cached promises when available and not expired.
   */
  async getMappingForInstance(
    jiraBaseUrl: string,
    jiraApiToken: string,
    jiraUsername: string,
    interactor: Interactor,
    maxResults: number = 100
  ): Promise<MappingResult> {
    const cacheKey = generateCacheKey(jiraBaseUrl, jiraApiToken, jiraUsername);
    const now = Date.now();
    
    // Check for existing non-expired cache entry
    const cacheEntry = MAPPING_CACHE.get(cacheKey);
    if (cacheEntry && (now - cacheEntry.timestamp) <= TTL_MS) {
      return cacheEntry.promise;
    }
    
    // Create new mapping promise
    const newPromise = this.createMapping(jiraBaseUrl, jiraApiToken, jiraUsername, interactor, maxResults);
    
    // Store the promise with current timestamp
    MAPPING_CACHE.set(cacheKey, {
      promise: newPromise,
      timestamp: now
    });
    
    return newPromise;
  }
  
  private async createMapping(
    jiraBaseUrl: string,
    jiraApiToken: string,
    jiraUsername: string,
    interactor: Interactor,
    maxResults: number
  ): Promise<MappingResult> {
    const mapper = new JiraFieldMapper(jiraBaseUrl, jiraApiToken, jiraUsername, interactor);
    return mapper.generateFieldMapping(maxResults);
  }
}

// Create a single export instance
export const jiraFieldMappingCache = new JiraFieldMappingCache();
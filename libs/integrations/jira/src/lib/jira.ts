import { Interactor } from '@coday/model/interactor'

export interface Jira {
  expand: string
  startAt: number
  maxResults: number
  total: number
  issues: JiraIssue[]
  nextPageToken?: string
}

export interface JiraCount {
  count: number
  jqlUrl?: string
}

export interface JiraSearchResponse {
  issues: JiraIssue[]
  nextPageToken?: string | null
  jqlUrl?: string
}

interface ParentFields {
  summary: string
  status: JiraStatus
  priority?: JiraPriority
  issuetype: JiraIssueType
}

interface ParentIssue {
  id: string
  key: string
  self: string
  fields: ParentFields
}

export interface JiraIssue {
  expand: string
  id: string
  self: string
  key: string
  fields: JiraFields
}

type CustomFields = {
  [key: `customfield_${number}`]: unknown
}

export interface StandardFields {
  summary?: string
  status?: JiraStatus
  priority?: JiraPriority
  assignee?: JiraUser
  reporter?: JiraUser
  created?: string
  updated?: string
  description?: string | null
  issuetype?: JiraIssueType
  project?: JiraProject
  labels?: string[]
  components?: JiraComponent[]
  fixVersions?: JiraVersion[]
  resolution?: object | null
  lastViewed?: string | null
  timeestimate?: any
  aggregatetimeoriginalestimate?: any
  aggregatetimeestimate?: any
  creator?: object
  subtasks?: []
  aggregateprogress?: { progress: number; total: number }
  progress?: { progress: number; total: number }
  votes?: object
  versions?: []
  issuelinks?: []
  statuscategorychangedate?: string
  // Adding missing fields
  worklog?: { startAt: number; maxResults: number; total: number; worklogs: [] }
  timespent?: null | number
  aggregatetimespent?: null | number
  resolutiondate?: string | null
  workratio?: number
  issuerestriction?: { issuerestrictions: object; shouldDisplay: boolean }
  watches?: {
    self: string
    watchCount: number
    isWatching: boolean
  }
  timeoriginalestimate?: null | number
  timetracking?: object
  security?: null | object
  attachment?: any
  duedate?: string | null
  comment?: {
    comments: any
    self: string
    maxResults: number
    total: number
    startAt: number
  }
  parent?: ParentIssue
}

export type JiraFields = StandardFields & CustomFields

interface JiraStatus {
  self: string
  id: string
  name: string
  description?: string
  iconUrl?: string
  statusCategory: {
    self: string
    id: number
    key: string
    colorName: string
    name: string
  }
}

interface JiraPriority {
  self: string
  id: string
  name: string
  iconUrl?: string
}

interface JiraUser {
  self: string
  accountId: string
  emailAddress?: string
  avatarUrls?: {
    [key: string]: string
  }
  displayName: string
  active: boolean
  timeZone?: string
  accountType?: string
}

interface JiraIssueType {
  self: string
  id: string
  description: string
  iconUrl: string
  name: string
  subtask: boolean
  avatarId?: number
  hierarchyLevel: number
}

interface JiraProject {
  self: string
  id: string
  key: string
  name: string
  simplified: boolean
  projectTypeKey?: string
  avatarUrls?: {
    [key: string]: string
  }
}

interface JiraComponent {
  self: string
  id: string
  name: string
  description?: string
}

interface JiraVersion {
  self: string
  id: string
  name: string
  archived: boolean
  released: boolean
  releaseDate?: string
  description?: string
}

export interface JiraField {
  id: string
  key: string
  name: string
  custom: boolean
  orderable: boolean
  navigable: boolean
  searchable: boolean
  clauseNames: string[]
  untranslatedName?: string
  schema?: JiraFieldSchema
  scope?: { type: string; project: object }
}

interface JiraFieldSchema {
  type: string
  items?: string
  system?: string
  custom?: string
  customId?: number
  configuration?: object
}

export interface JiraSearchParams {
  jiraBaseUrl: string
  jiraApiToken: string
  jiraUsername: string
  interactor: Interactor
}

export interface VisibleField {
  value: string
  displayName: string
  orderable?: string
  searchable?: string
  cfid?: string
  auto?: string
  operators: string[]
  types: string[]
  deprecated?: string
  deprecatedSearcherKey?: string
}

export interface AutocompleteDataResponse {
  visibleFieldNames: VisibleField[]
  visibleFunctionNames?: any
  jqlReservedWords?: any
}

export interface FieldMappingDescription {
  customFields: string
  jqlResearchDescription: string
}

export type LightWeightIssues = Record<JiraField['key'], Record<keyof JiraFields, any>>

export type LightWeightSearchResponse = {
  issues: LightWeightIssues
  nextPageToken?: string | null | undefined
  jqlUrl?: string
}

/**
 * Interface for the Jira issue creation request
 */
export interface CreateJiraIssueRequest {
  projectKey?: string
  project?: { key: string }
  summary: string
  description?: string
  issuetype?: string
  assignee?: string
  reporter?: { id?: string; name?: string }
  priority?: string
  labels?: string[]
  components?: string[]
  fixVersions?: string[]
  duedate?: string
  // Direct parent relationship - can be set during issue creation
  parent?: {
    key: string // The issue key of the parent
  }
  // Issues to link to this issue (especially useful for epics)
  linkedIssues?: {
    key: string
    linkType?: string // Default is "is part of" for epics
    isEpicLink?: boolean // Set to true to create Epic-Issue relationship
  }[]
  // For retry mechanism
  error?: string
  partialRequest?: Partial<CreateJiraIssueRequest>
  // Custom fields
  [key: `customfield_${number}`]: any
}

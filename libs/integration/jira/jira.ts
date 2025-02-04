import {Interactor} from "@coday/model/interactor";

export interface Jira {
    expand: string;
    startAt: number;
    maxResults: number;
    total: number;
    issues: JiraIssue[];
}

export interface JiraIssue {
    expand: string;
    id: string;
    self: string;
    key: string;
    fields: JiraFields;
}

export interface JiraFields {
    summary: string;
    status: JiraStatus;
    priority?: JiraPriority;
    assignee?: JiraUser;
    reporter?: JiraUser;
    created: string;
    updated: string;
    description?: string;
    issuetype: JiraIssueType;
    project: JiraProject;
    labels?: string[];
    components?: JiraComponent[];
    fixVersions?: JiraVersion[];
    customfields?: JiraCustomField;
}

export interface JiraCustomField {
    id: string
    name: string
    key: string
    type: string
}

interface JiraStatus {
    self: string;
    id: string;
    name: string;
    description?: string;
    iconUrl?: string;
    statusCategory: {
        self: string;
        id: number;
        key: string;
        colorName: string;
        name: string;
    };
}

interface JiraPriority {
    self: string;
    id: string;
    name: string;
    iconUrl?: string;
}

interface JiraUser {
    self: string;
    accountId: string;
    emailAddress?: string;
    avatarUrls?: {
        [key: string]: string;
    };
    displayName: string;
    active: boolean;
    timeZone?: string;
}

interface JiraIssueType {
    self: string;
    id: string;
    description: string;
    iconUrl: string;
    name: string;
    subtask: boolean;
    avatarId?: number;
}

interface JiraProject {
    self: string;
    id: string;
    key: string;
    name: string;
    projectTypeKey?: string;
    avatarUrls?: {
        [key: string]: string;
    };
}

interface JiraComponent {
    self: string;
    id: string;
    name: string;
    description?: string;
}

interface JiraVersion {
    self: string;
    id: string;
    name: string;
    archived: boolean;
    released: boolean;
    releaseDate?: string;
}

export interface JiraField {
    id: string;
    key?: string;
    name: string;
    custom: boolean;
    orderable: boolean;
    navigable: boolean;
    searchable: boolean;
    clauseNames: string[];
    schema?: JiraFieldSchema;
}

interface JiraFieldSchema {
    type: string;
    items?: string;
    system?: string;
    custom?: string;
    customId?: number;
}

export interface JiraSearchParams {
    jiraBaseUrl: string
    jiraApiToken: string
    jiraUsername: string
    interactor: Interactor
}
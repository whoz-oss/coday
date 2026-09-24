export interface CaseTerminator {
  terminate(caseId: string): Promise<void>
}

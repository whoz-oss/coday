import { HttpClient, HttpParams } from '@angular/common/http'
import { inject, Injectable } from '@angular/core'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'
import { CustomHttpParameterCodec } from '../lib/encoder'
import { ExchangeDeleteResponse } from '../lib/model/exchange-delete-response'
import { ExchangeDirectoryEntry } from '../lib/model/exchange-directory-entry'
import { ExchangeFileEntry, ExchangeFileEntryScopeEnum } from '../lib/model/exchange-file-entry'

/**
 * ExchangeFolderApiService — hand-written client for the exchange folder operations.
 *
 * The folder routes exist for both scopes (`/api/cases/{caseId}` and `/api/namespaces/{namespaceId}`):
 *
 * - `POST   {base}/files?directory=`          upload a file into a folder (the generated client has no `directory`);
 * - `POST   {base}/files/directory?path=`     create a folder;
 * - `DELETE {base}/files/directory?path=`     delete a folder and everything in it;
 * - `GET    {base}/files/archive?path=`       download a folder (empty path: the whole scope) as one ZIP.
 *
 * They are kept out of the generated client on purpose: the frontend must not depend on a regeneration to ship,
 * and a ZIP stream is not a JSON operation the generator types well. Paths are relative to the scope root.
 */
@Injectable({ providedIn: 'root' })
export class ExchangeFolderApiService {
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)

  /** Upload [file] into [directory] (relative to the scope root; empty string is the root). */
  uploadFile(
    scope: ExchangeFileEntryScopeEnum,
    id: string,
    directory: string,
    file: Blob
  ): Observable<ExchangeFileEntry> {
    const body = new FormData()
    body.append('file', file)
    return this.http.post<ExchangeFileEntry>(`${this.filesUrl(scope, id)}`, body, {
      params: this.params(directory ? { directory } : {}),
      ...this.credentials(),
    })
  }

  /** Create the folder at [path]; answers 409 when a file or folder already uses it. */
  createDirectory(scope: ExchangeFileEntryScopeEnum, id: string, path: string): Observable<ExchangeDirectoryEntry> {
    return this.http.post<ExchangeDirectoryEntry>(`${this.filesUrl(scope, id)}/directory`, null, {
      params: this.params({ path }),
      ...this.credentials(),
    })
  }

  /** Delete the folder at [path] with its content; answers 409, deleting nothing, when it holds a protected entry. */
  deleteDirectory(scope: ExchangeFileEntryScopeEnum, id: string, path: string): Observable<ExchangeDeleteResponse> {
    return this.http.delete<ExchangeDeleteResponse>(`${this.filesUrl(scope, id)}/directory`, {
      params: this.params({ path }),
      ...this.credentials(),
    })
  }

  /** The folder at [path] (empty string: the whole scope) as one ZIP. */
  downloadArchive(scope: ExchangeFileEntryScopeEnum, id: string, path: string): Observable<Blob> {
    return this.http.get(`${this.filesUrl(scope, id)}/archive`, {
      params: this.params({ path }),
      headers: { Accept: 'application/zip' },
      responseType: 'blob',
      ...this.credentials(),
    })
  }

  private filesUrl(scope: ExchangeFileEntryScopeEnum, id: string): string {
    const root = scope === ExchangeFileEntryScopeEnum.CASE ? 'cases' : 'namespaces'
    return `${this.config.basePath ?? ''}/api/${root}/${encodeURIComponent(id)}/files`
  }

  /** Same credentials policy as the generated services, so a deployment that sends cookies does so here too. */
  private credentials(): { withCredentials?: boolean } {
    return this.config.withCredentials ? { withCredentials: true } : {}
  }

  /** Same codec as the generated services: the default one leaves `+` as is, which a server reads as a space. */
  private params(values: Record<string, string>): HttpParams {
    let params = new HttpParams({ encoder: new CustomHttpParameterCodec() })
    for (const [key, value] of Object.entries(values)) params = params.set(key, value)
    return params
  }
}

import { HttpClient, HttpParams } from '@angular/common/http'
import { inject, Injectable } from '@angular/core'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'
import { ExchangeDeleteResponse } from '../lib/model/exchange-delete-response'
import { ExchangeDirectoryEntry } from '../lib/model/exchange-directory-entry'
import { ExchangeFileEntry, ExchangeFileEntryScopeEnum } from '../lib/model/exchange-file-entry'

/**
 * ExchangeFolderApiService — hand-written client for the folder-aware exchange routes.
 *
 * The routes below sit next to the generated `ExchangeControllerService` ones, for both scopes:
 *
 * - `POST   {base}/files?directory=`        upload into a folder (omit `directory` for the scope root);
 * - `POST   {base}/files/directory?path=`   create a folder;
 * - `DELETE {base}/files/directory?path=`   delete a folder and everything in it;
 * - `GET    {base}/files/archive?path=`     the folder (or the whole scope when `path` is empty) as one ZIP.
 *
 * `{base}` is `/api/cases/{caseId}` or `/api/namespaces/{namespaceId}`. They are called by hand so the
 * frontend does not depend on a regenerated client; an upload without a folder keeps using the generated one.
 */
@Injectable({ providedIn: 'root' })
export class ExchangeFolderApiService {
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)

  /** Upload [file] into [directory] of the scope (relative to its root, forward slashes). */
  uploadFile(
    scope: ExchangeFileEntryScopeEnum,
    id: string,
    directory: string,
    file: Blob
  ): Observable<ExchangeFileEntry> {
    const body = new FormData()
    body.append('file', file)
    let params = new HttpParams()
    if (directory) params = params.set('directory', directory)
    return this.http.post<ExchangeFileEntry>(this.url(scope, id, '/files'), body, { params })
  }

  /** Create the folder at [path] (relative to the scope root). Answers 409 if something is already there. */
  createDirectory(scope: ExchangeFileEntryScopeEnum, id: string, path: string): Observable<ExchangeDirectoryEntry> {
    return this.http.post<ExchangeDirectoryEntry>(this.url(scope, id, '/files/directory'), null, {
      params: new HttpParams().set('path', path),
    })
  }

  /** Delete the folder at [path] with its content. Answers 409 if it holds a protected entry. */
  deleteDirectory(scope: ExchangeFileEntryScopeEnum, id: string, path: string): Observable<ExchangeDeleteResponse> {
    return this.http.delete<ExchangeDeleteResponse>(this.url(scope, id, '/files/directory'), {
      params: new HttpParams().set('path', path),
    })
  }

  /** The folder at [path] (empty for the whole scope) as one ZIP archive. */
  downloadArchive(scope: ExchangeFileEntryScopeEnum, id: string, path: string): Observable<Blob> {
    let params = new HttpParams()
    if (path) params = params.set('path', path)
    return this.http.get(this.url(scope, id, '/files/archive'), { params, responseType: 'blob' })
  }

  private url(scope: ExchangeFileEntryScopeEnum, id: string, suffix: string): string {
    const root = scope === ExchangeFileEntryScopeEnum.CASE ? 'cases' : 'namespaces'
    return `${this.config.basePath}/api/${root}/${encodeURIComponent(id)}${suffix}`
  }
}

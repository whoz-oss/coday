import { HttpClient } from '@angular/common/http'
import { TestBed } from '@angular/core/testing'
import { Configuration } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { SideQuestionApiService } from './side-question-api.service'

describe('SideQuestionApiService', () => {
  it('posts the question to the side-questions endpoint of the case, under the configured base path', () => {
    const http = { post: jest.fn().mockReturnValue(of({ answer: 'a' })) }
    TestBed.configureTestingModule({
      providers: [
        { provide: HttpClient, useValue: http },
        { provide: Configuration, useValue: { basePath: 'http://agentos' } },
      ],
    })

    let received: unknown
    TestBed.inject(SideQuestionApiService)
      .ask('c-1', 'what now?')
      .subscribe((r) => (received = r))

    expect(http.post).toHaveBeenCalledWith('http://agentos/api/cases/c-1/side-questions', { question: 'what now?' })
    expect(received).toEqual({ answer: 'a' })
    TestBed.resetTestingModule()
  })
})

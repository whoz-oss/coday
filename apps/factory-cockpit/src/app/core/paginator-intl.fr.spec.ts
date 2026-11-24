import { FrPaginatorIntl } from './paginator-intl.fr'

describe('FrPaginatorIntl', () => {
  it('uses explicit English paginator labels', () => {
    const intl = new FrPaginatorIntl()
    expect(intl.itemsPerPageLabel).toBe('Items per page')
    expect(intl.nextPageLabel).toBe('Next page')
    expect(intl.previousPageLabel).toBe('Previous page')
    expect(intl.getRangeLabel(1, 25, 60)).toBe('26 – 50 of 60')
    expect(intl.getRangeLabel(0, 25, 0)).toBe('0 of 0')
  })
})

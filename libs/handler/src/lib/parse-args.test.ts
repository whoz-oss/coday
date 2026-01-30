import { parseArgs, KeyInput } from './parse-args'

describe('parseArgs', () => {
  const basicInputs: KeyInput[] = [{ key: 'foo', alias: 'f' }, { key: 'bar', alias: 'b' }, { key: 'baz' }]

  it('parses basic long-form key-value arguments', () => {
    const res = parseArgs('--foo=hello --bar=world', basicInputs)
    expect(res.foo).toBe('hello')
    expect(res.bar).toBe('world')
    expect(res.baz).toBeUndefined()
    expect(res.rest).toBe('')
  })

  it('parses basic short-form alias key-value arguments', () => {
    const res = parseArgs('-f=hi -b=there', basicInputs)
    expect(res.foo).toBe('hi')
    expect(res.bar).toBe('there')
    expect(res.rest).toBe('')
  })

  it('parses flags (no value) in long and short form', () => {
    const res = parseArgs('--foo --bar -b -f', basicInputs)
    expect(res.foo).toBe(true)
    expect(res.bar).toBe(true)
    // Only the first occurrence per key/alias is consumed, so -b -f remain
    expect(res.rest).toBe('-b -f')
  })

  it('parses key-value pairs with colon separator', () => {
    const res = parseArgs('--foo:val1 -b:val2', basicInputs)
    expect(res.foo).toBe('val1')
    expect(res.bar).toBe('val2')
    expect(res.rest).toBe('')
  })

  it('handles mixed arguments and preserves rest', () => {
    const cmd = '--foo=FOO some text -b:BAR trailing'
    const res = parseArgs(cmd, basicInputs)
    expect(res.foo).toBe('FOO')
    expect(res.bar).toBe('BAR')
    // The remaining words are preserved
    expect(res.rest).toBe('some text trailing')
  })

  it('ignores keys not in input array and leaves them in rest', () => {
    const res = parseArgs('--notme=123 untouched', basicInputs)
    expect(res.foo).toBeUndefined()
    expect(res.bar).toBeUndefined()
    expect(res.rest).toBe('--notme=123 untouched')
  })

  it('handles empty command', () => {
    const res = parseArgs('', basicInputs)
    expect(res).toEqual({ rest: '' })
  })

  it('handles empty inputs array (all command is rest)', () => {
    const res = parseArgs('--foo=anything bar', [])
    expect(res.rest).toBe('--foo=anything bar')
    expect(Object.keys(res)).toEqual(['rest'])
  })

  it('handles duplicate flags, takes first match and removes only one', () => {
    const res = parseArgs('--foo --foo trailing', basicInputs)
    expect(res.foo).toBe(true)
    // Only one --foo consumed, the other should be in rest
    expect(res.rest).toBe('--foo trailing')
  })

  it('handles argument order variance', () => {
    const res = parseArgs('before -f:xyz after --bar:abc', basicInputs)
    expect(res.foo).toBe('xyz')
    expect(res.bar).toBe('abc')
    expect(res.rest).toBe('before after')
  })

  it('handles input with key but no alias', () => {
    const res = parseArgs('--baz=123 hello', basicInputs)
    expect(res.baz).toBe('123')
    expect(res.rest).toBe('hello')
  })

  it('handles flag with key but no alias', () => {
    const res = parseArgs('--baz', basicInputs)
    expect(res.baz).toBe(true)
    expect(res.rest).toBe('')
  })

  it('is type safe for returned shape', () => {
    const r = parseArgs('--foo=val --bar', basicInputs)
    // TypeScript: r.foo exists and is string or boolean
    const foo: string | boolean | undefined = r.foo
    expect(foo).toBe('val')
    const bar: string | boolean | undefined = r.bar
    expect(bar).toBe(true)
    // .rest always string
    expect(typeof r.rest).toBe('string')
  })
})

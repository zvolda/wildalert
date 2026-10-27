import { describe, expect, it } from 'vitest'
import {
  forSubmission,
  hasErrors,
  validateEmail,
  validatePhone,
  validateRegistration,
} from '../validation'

describe('validatePhone', () => {
  // These cases are the server's E164 regex in HunterDtos.kt, read as examples. If the server's
  // rule changes, one of these should start failing.
  it('accepts a Czech mobile in international format', () => {
    expect(validatePhone('+420123456789')).toBeUndefined()
  })

  it('accepts the shortest and longest E.164 numbers', () => {
    expect(validatePhone('+1234567')).toBeUndefined() // + then 7 digits
    expect(validatePhone('+123456789012345')).toBeUndefined() // + then 15 digits
  })

  it('rejects a number without the leading +', () => {
    expect(validatePhone('420123456789')).toBeDefined()
  })

  it('rejects a country code starting with zero', () => {
    expect(validatePhone('+0123456789')).toBeDefined()
  })

  it('rejects a number that is too short or too long', () => {
    expect(validatePhone('+42012')).toBeDefined() // 5 digits
    expect(validatePhone('+1234567890123456')).toBeDefined() // 16 digits
  })

  it('rejects spaces and punctuation, which hunters will type', () => {
    expect(validatePhone('+420 123 456 789')).toBeDefined()
    expect(validatePhone('+420-123-456-789')).toBeDefined()
  })

  it('asks for a number when the field is empty', () => {
    expect(validatePhone('')).toBe('Enter your mobile number')
    expect(validatePhone('   ')).toBe('Enter your mobile number')
  })
})

describe('validateEmail', () => {
  it('accepts an ordinary address', () => {
    expect(validateEmail('hunter@example.com')).toBeUndefined()
  })

  it('accepts a dotless domain, because the server does', () => {
    // Not a guess: POST /api/hunters with "hunter@localhost" returned 201 against a running
    // user-account on 2026-10-27. The client must not reject what the server accepts.
    expect(validateEmail('hunter@localhost')).toBeUndefined()
  })

  it('rejects an address with no @ or no local part', () => {
    expect(validateEmail('hunter.example.com')).toBeDefined()
    expect(validateEmail('@example.com')).toBeDefined()
    expect(validateEmail('hunter@')).toBeDefined()
  })

  it('asks for an address when the field is empty', () => {
    expect(validateEmail('  ')).toBe('Enter your email address')
  })
})

describe('forSubmission', () => {
  it('trims the email, because the server validates before it normalises', () => {
    // A real 400 from user-account for "Browser.Check@Example.COM " (trailing space):
    // {"errors":{"email":"must be a valid email address"}} -- Jakarta's @Email sees the raw
    // value, and HunterService.register only trims afterwards.
    expect(forSubmission({ email: '  Hunter@Example.com ', phone: '+420123456789' }))
      .toEqual({ email: 'Hunter@Example.com', phone: '+420123456789' })
  })

  it('trims the phone too', () => {
    expect(forSubmission({ email: 'a@b.c', phone: ' +420123456789 ' }).phone)
      .toBe('+420123456789')
  })

  it('does not change the case of the email', () => {
    // The server lowercases it and the UI renders what comes back, so there is nothing for the
    // client to second-guess here.
    expect(forSubmission({ email: 'MiXeD@Case.COM', phone: '+420123456789' }).email)
      .toBe('MiXeD@Case.COM')
  })
})

describe('validateRegistration', () => {
  it('returns nothing to complain about for a valid form', () => {
    const errors = validateRegistration({
      email: 'hunter@example.com',
      phone: '+420123456789',
    })
    expect(errors).toEqual({})
    expect(hasErrors(errors)).toBe(false)
  })

  it('reports both fields at once rather than one at a time', () => {
    const errors = validateRegistration({ email: 'nope', phone: 'nope' })
    expect(Object.keys(errors).sort()).toEqual(['email', 'phone'])
    expect(hasErrors(errors)).toBe(true)
  })
})

import { describe, expect, it } from 'vitest'
import {
  forSubmission,
  hasErrors,
  loginForSubmission,
  validateEmail,
  validateLogin,
  validateNewPassword,
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
    expect(forSubmission({ email: '  Hunter@Example.com ', phone: '+420123456789', password: 'correct-horse' }))
      .toEqual({ email: 'Hunter@Example.com', phone: '+420123456789', password: 'correct-horse' })
  })

  it('trims the phone too', () => {
    expect(forSubmission({ email: 'a@b.c', phone: ' +420123456789 ', password: 'correct-horse' }).phone)
      .toBe('+420123456789')
  })

  it('does not change the case of the email', () => {
    // The server lowercases it and the UI renders what comes back, so there is nothing for the
    // client to second-guess here.
    expect(forSubmission({ email: 'MiXeD@Case.COM', phone: '+420123456789', password: 'correct-horse' }).email)
      .toBe('MiXeD@Case.COM')
  })

  it('leaves the password exactly as typed, spaces and all', () => {
    // Deliberately unlike the other two fields. A space is a valid password character, and
    // trimming would store something different from what the hunter typed -- which they would
    // meet as a failed login later with no way to work out why.
    expect(forSubmission({ email: 'a@b.c', phone: '+420123456789', password: '  spaced out  ' }).password)
      .toBe('  spaced out  ')
  })
})

describe('validateRegistration', () => {
  it('returns nothing to complain about for a valid form', () => {
    const errors = validateRegistration({
      email: 'hunter@example.com',
      phone: '+420123456789',
      password: 'correct-horse',
    })
    expect(errors).toEqual({})
    expect(hasErrors(errors)).toBe(false)
  })

  it('reports every field at once rather than one at a time', () => {
    const errors = validateRegistration({ email: 'nope', phone: 'nope', password: 'short' })
    expect(Object.keys(errors).sort()).toEqual(['email', 'password', 'phone'])
    expect(hasErrors(errors)).toBe(true)
  })
})

describe('validateNewPassword', () => {
  it('accepts a password of exactly the minimum length', () => {
    expect(validateNewPassword('0123456789')).toBeUndefined()
  })

  it('rejects one character short', () => {
    expect(validateNewPassword('012345678')).toBe('Use at least 10 characters')
  })

  it('accepts exactly 72 characters, the most BCrypt can see', () => {
    expect(validateNewPassword('a'.repeat(72))).toBeUndefined()
  })

  it('rejects 73 characters, because BCrypt would ignore the rest', () => {
    // Not a made-up limit: BCrypt truncates at 72 bytes, so a 73-character password would be
    // openable with its first 72 characters. The server rejects it too (PASSWORD_MAX_LENGTH).
    expect(validateNewPassword('a'.repeat(73))).toBe('Use at most 72 characters')
  })

  it('asks for a password when the field is empty', () => {
    expect(validateNewPassword('')).toBe('Choose a password')
  })

  it('counts spaces as characters rather than stripping them', () => {
    // '  a  ' is 5 characters; if it were trimmed first it would be 1 and the message would be
    // the same, so this asserts the length used is the untrimmed one via a 10-space password.
    expect(validateNewPassword(' '.repeat(10))).toBeUndefined()
  })
})

describe('validateLogin', () => {
  it('accepts any non-empty password, however short', () => {
    // Deliberate: restating the 10-character rule here would announce the policy and lock out
    // anyone whose password predates it. The server answers a bad password with one 401.
    expect(validateLogin({ email: 'hunter@example.com', password: 'x' })).toEqual({})
  })

  it('asks for a password when it is missing', () => {
    expect(validateLogin({ email: 'hunter@example.com', password: '' }))
      .toEqual({ password: 'Enter your password' })
  })

  it('still checks the email looks like an address', () => {
    expect(validateLogin({ email: 'nope', password: 'correct-horse' }).email).toBeDefined()
  })
})

describe('loginForSubmission', () => {
  it('trims the email but not the password', () => {
    expect(loginForSubmission({ email: ' a@b.c ', password: ' secret ' }))
      .toEqual({ email: 'a@b.c', password: ' secret ' })
  })
})

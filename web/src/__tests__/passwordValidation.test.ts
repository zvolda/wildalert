import { describe, expect, it } from 'vitest'
import {
  validateChangePassword,
  validateConfirmPasswordReset,
  validatePasswordResetRequest,
} from '../validation'

/**
 * The password flows' client-side rules. A separate file from validation.test.ts because these
 * three functions share a theme -- which value gets the full policy and which gets presence only
 * -- and that distinction is the thing worth being able to read in one place.
 */

describe('validateChangePassword', () => {
  it('accepts a long enough new password with the current one supplied', () => {
    expect(
      validateChangePassword({ currentPassword: 'old', newPassword: 'battery-staple-9' }),
    ).toEqual({})
  })

  it('asks for the current password when it is missing', () => {
    expect(
      validateChangePassword({ currentPassword: '', newPassword: 'battery-staple-9' }),
    ).toEqual({ currentPassword: 'Enter your current password' })
  })

  it('applies the full policy to the new password', () => {
    expect(
      validateChangePassword({ currentPassword: 'old', newPassword: 'short' }).newPassword,
    ).toBe('Use at least 10 characters')
  })

  it('does not apply the policy to the current password', () => {
    // The same reasoning as the login form: restating the rule on a value being *verified*
    // announces the policy, and would reject a password chosen before the rule existed.
    expect(
      validateChangePassword({ currentPassword: 'x', newPassword: 'battery-staple-9' }),
    ).toEqual({})
  })

  it('refuses a new password identical to the current one', () => {
    // The server would accept this happily. It is refused here because a hunter who has just been
    // told every other device was signed out should not have changed nothing.
    expect(
      validateChangePassword({
        currentPassword: 'battery-staple-9',
        newPassword: 'battery-staple-9',
      }).newPassword,
    ).toBe('Choose a password different from your current one')
  })

  it('reports the length problem rather than the sameness one when both apply', () => {
    // Both are true for 'short'/'short', and the length message is the actionable one.
    expect(
      validateChangePassword({ currentPassword: 'short', newPassword: 'short' }).newPassword,
    ).toBe('Use at least 10 characters')
  })
})

describe('validatePasswordResetRequest', () => {
  it('accepts a well-formed address', () => {
    expect(validatePasswordResetRequest({ email: 'hunter@example.com' })).toEqual({})
  })

  it('asks for an address when it is empty', () => {
    expect(validatePasswordResetRequest({ email: '' }).email).toBe('Enter your email address')
  })

  it('rejects something that is not an address at all', () => {
    expect(validatePasswordResetRequest({ email: 'nope' }).email).toBeDefined()
  })
})

describe('validateConfirmPasswordReset', () => {
  it('accepts a long enough password', () => {
    expect(validateConfirmPasswordReset('battery-staple-9')).toEqual({})
  })

  it('applies the policy, since this is where the password is chosen', () => {
    expect(validateConfirmPasswordReset('short').newPassword).toBe('Use at least 10 characters')
  })

  it('says nothing about the token', () => {
    // The token came from the emailed link, not the hunter, so there is nothing to blame them
    // for. A dead link is the server's single generic 400, shown as a banner.
    expect(Object.keys(validateConfirmPasswordReset('battery-staple-9'))).toEqual([])
  })
})

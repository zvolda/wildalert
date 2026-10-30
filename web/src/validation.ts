import type { FieldErrors, LoginRequest, RegisterHunterRequest } from './types'

/**
 * Copied deliberately from PASSWORD_MIN_LENGTH / PASSWORD_MAX_LENGTH in HunterDtos.kt. The upper
 * bound is BCrypt's: it truncates at 72 bytes, so characters past that would silently do nothing.
 */
const PASSWORD_MIN_LENGTH = 10
const PASSWORD_MAX_LENGTH = 72

/**
 * Client-side copies of the server's rules, so the form can answer without a round trip.
 * The server stays the authority: a 400 always repaints these fields (see api.ts).
 */

/**
 * Copied deliberately from the `E164` constant in HunterDtos.kt: a leading + then 7-15 digits,
 * first digit non-zero. Duplicated rather than imported because there is no shared schema --
 * this comment is the link between the two copies.
 */
const E164 = /^\+[1-9]\d{6,14}$/

export function validatePhone(phone: string): string | undefined {
  const value = phone.trim()
  if (value === '') return 'Enter your mobile number'
  if (!E164.test(value)) return 'Use the international format, e.g. +420123456789'
  return undefined
}

export function validateEmail(email: string): string | undefined {
  const value = email.trim()
  if (value === '') return 'Enter your email address'
  // Deliberately lenient: the server uses Jakarta's @Email, which accepts addresses this
  // would-be-stricter client has no business rejecting (it does not insist on a dot in the
  // domain, for instance). Being laxer here means the client never blocks something the
  // server would have accepted; anything genuinely wrong still comes back as a 400.
  if (!/^[^\s@]+@[^\s@]+$/.test(value)) return 'That does not look like an email address'
  return undefined
}

/**
 * The password rule, for the registration form only.
 *
 * Note it counts characters of the **untrimmed** value: spaces are legitimate password
 * characters, and silently stripping them would mean the password a hunter typed is not the one
 * we store, which they would discover at their next login and never be able to explain.
 */
export function validateNewPassword(password: string): string | undefined {
  if (password === '') return 'Choose a password'
  if (password.length < PASSWORD_MIN_LENGTH) {
    return `Use at least ${PASSWORD_MIN_LENGTH} characters`
  }
  if (password.length > PASSWORD_MAX_LENGTH) {
    // Not a server-side limit we are guessing at -- past 72 bytes BCrypt ignores the rest.
    return `Use at most ${PASSWORD_MAX_LENGTH} characters`
  }
  return undefined
}

/**
 * What actually gets sent. Trimming matters more than it looks: the server validates the **raw**
 * body with Jakarta's `@Email`, and only normalises (trim + lowercase) afterwards in
 * `HunterService.register`. So a trailing space is rejected with
 * `"must be a valid email address"` — verified against a running user-account on 2026-10-27 —
 * even though the client's own check trims before testing and would happily accept it.
 *
 * Without this, the form says the address is fine and the server disagrees, which is the most
 * confusing failure a form can produce. Browsers also leave a trailing space behind routinely
 * when autofilling or pasting.
 */
export function forSubmission(request: RegisterHunterRequest): RegisterHunterRequest {
  return {
    email: request.email.trim(),
    phone: request.phone.trim(),
    // **Not trimmed, deliberately.** A space is a perfectly good password character, and
    // trimming here would store something other than what was typed -- a mismatch the hunter
    // would meet at their next login with no way to work out why.
    password: request.password,
  }
}

/** Validates the whole form. An empty object means "nothing to complain about". */
export function validateRegistration(request: RegisterHunterRequest): FieldErrors {
  const errors: FieldErrors = {}
  const email = validateEmail(request.email)
  if (email) errors.email = email
  const phone = validatePhone(request.phone)
  if (phone) errors.phone = phone
  const password = validateNewPassword(request.password)
  if (password) errors.password = password
  return errors
}

/** The login form's rules: presence only. */
export function loginForSubmission(request: LoginRequest): LoginRequest {
  return { email: request.email.trim(), password: request.password }
}

/**
 * Validates the login form.
 *
 * Deliberately does *not* apply the password policy. Restating "at least 10 characters" on a
 * login form would announce the rule to anyone, and would lock out a hunter whose password
 * predates whatever the rule becomes. Presence is all the client can usefully check; the server
 * answers everything else with one 401.
 */
export function validateLogin(request: LoginRequest): FieldErrors {
  const errors: FieldErrors = {}
  const email = validateEmail(request.email)
  if (email) errors.email = email
  if (request.password === '') errors.password = 'Enter your password'
  return errors
}

export function hasErrors(errors: FieldErrors): boolean {
  return Object.keys(errors).length > 0
}

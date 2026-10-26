import { describe, expect, it } from 'vitest'
import { parseProblem } from '../problemDetail'

/*
 * The bodies below are Spring's ProblemDetail serialization as produced by
 * services/user-account/.../web/GlobalExceptionHandler.kt.
 *
 * NOT YET RECONCILED against a running user-account -- they are written from the handler's code
 * and Spring's documented shape. Capture the two real responses (a 400 and a 409 from
 * POST /api/hunters) and paste them in verbatim; the value of these tests is that they are not
 * paraphrased, and a guessed shape is exactly how the GoSMS parsing bug got in.
 */

const VALIDATION_400 = {
  type: 'about:blank',
  title: 'Bad Request',
  status: 400,
  detail: 'Request validation failed',
  instance: '/api/hunters',
  errors: {
    phone: 'must be E.164 format, e.g. +420123456789',
  },
}

const DUPLICATE_409 = {
  type: 'about:blank',
  title: 'Conflict',
  status: 409,
  detail: "A hunter with email 'hunter@example.com' already exists",
  instance: '/api/hunters',
}

describe('parseProblem', () => {
  it('puts a validation message on the field it belongs to', () => {
    const { message, fieldErrors } = parseProblem(400, VALIDATION_400)
    expect(fieldErrors).toEqual({ phone: 'must be E.164 format, e.g. +420123456789' })
    // Nothing left over, so no banner competing with the field message.
    expect(message).toBe('')
  })

  it('handles several field errors in one response', () => {
    const { fieldErrors } = parseProblem(400, {
      ...VALIDATION_400,
      errors: { email: 'must be a valid email address', phone: 'must not be blank' },
    })
    expect(fieldErrors).toEqual({
      email: 'must be a valid email address',
      phone: 'must not be blank',
    })
  })

  it('surfaces a validation error for a field the form does not render', () => {
    // Rather than silently dropping it and leaving the user with a form that just refuses.
    const { message, fieldErrors } = parseProblem(400, {
      ...VALIDATION_400,
      errors: { somethingNew: 'must not be null' },
    })
    expect(fieldErrors).toEqual({})
    expect(message).toBe('somethingNew: must not be null')
  })

  it('shows a duplicate email against the email field, not as a banner', () => {
    const { message, fieldErrors } = parseProblem(409, DUPLICATE_409)
    expect(fieldErrors).toEqual({
      email: "A hunter with email 'hunter@example.com' already exists",
    })
    expect(message).toBe('')
  })

  it('still explains a 409 that arrives with no body', () => {
    const { fieldErrors } = parseProblem(409, undefined)
    expect(fieldErrors.email).toBe('That email address is already registered')
  })

  it('degrades to a generic message instead of throwing on an unusable body', () => {
    // A proxy error page, an empty body, or JSON that is not an object.
    for (const body of [undefined, '', 'Bad Gateway', 42, [], null]) {
      const { message, fieldErrors } = parseProblem(502, body)
      expect(fieldErrors).toEqual({})
      expect(message).toContain('502')
    }
  })

  it('prefers the server detail for an unexpected status', () => {
    const { message } = parseProblem(500, { detail: 'Internal error', status: 500 })
    expect(message).toBe('Internal error')
  })
})

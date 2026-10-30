import type { FieldErrors, FormField, ProblemDetail } from './types'

/**
 * Turns a failed response from user-account into something the form can render.
 *
 * Kept pure -- status plus an already-parsed body in, a message and field errors out -- so it
 * can be unit-tested against response bodies copied verbatim from the real service. That habit
 * is what caught the GoSMS response-shape mistake in Phase 6 slice 12.
 */
export interface ParsedProblem {
  /** Shown as a banner. Empty when every part of the problem landed on a field instead. */
  message: string
  fieldErrors: FieldErrors
  /**
   * The HTTP status, kept so a caller can tell one failure from another without re-reading the
   * message. `fetchMe` needs it: a 401 there means "nobody is signed in", which is an ordinary
   * answer rather than an error. Absent for a transport failure, where there was no response.
   */
  status?: number
}

/**
 * The runtime allowlist. A name missing from here degrades into banner text rather than landing
 * on its input -- which is a deliberate fallback, but a silent one, so adding a field to
 * `FormField` without adding it here is the easy half of the job done.
 *
 * `token` is **deliberately absent**: a validation message about the reset token belongs in the
 * banner, because there is no token input on the screen for it to attach to.
 */
const FORM_FIELDS: readonly FormField[] = [
  'email',
  'phone',
  'password',
  'currentPassword',
  'newPassword',
]

function isFormField(name: string): name is FormField {
  return (FORM_FIELDS as readonly string[]).includes(name)
}

export function parseProblem(status: number, body: unknown): ParsedProblem {
  const problem = asProblemDetail(body)

  // 400: GlobalExceptionHandler.handleValidation puts a field -> message map in `errors`.
  if (status === 400 && problem?.errors) {
    const fieldErrors: FieldErrors = {}
    const unmapped: string[] = []
    for (const [field, message] of Object.entries(problem.errors)) {
      if (isFormField(field)) {
        fieldErrors[field] = message
      } else {
        // A field we do not render (the contract grew, or a nested path). Do not lose it --
        // silently dropping a validation message is how a form becomes unexplainably stuck.
        unmapped.push(`${field}: ${message}`)
      }
    }
    return { message: unmapped.join('; '), fieldErrors, status }
  }

  // 409: the email is taken. That is a problem with a specific field, so show it there rather
  // than as a banner the user has to connect back to the input themselves.
  if (status === 409) {
    return {
      message: '',
      fieldErrors: { email: problem?.detail ?? 'That email address is already registered' },
      status,
    }
  }

  // 401 deliberately falls through to a banner. A failed login cannot be attributed to the email
  // or the password without revealing which one was wrong, which is the whole reason the server
  // answers both with one message -- so showing it next to a single field would undo that.
  return { message: genericMessage(status, problem), fieldErrors: {}, status }
}

function genericMessage(status: number, problem: ProblemDetail | undefined): string {
  if (problem?.detail) return problem.detail
  if (status >= 500) return `The server had a problem (HTTP ${status}). Please try again.`
  return `The request was rejected (HTTP ${status}).`
}

/**
 * A body is only usable if it is a JSON object. A proxy error page, an empty body or a plain
 * string all end up here, and all have to degrade to a generic message rather than throw --
 * an error path that itself throws loses the original error.
 */
function asProblemDetail(body: unknown): ProblemDetail | undefined {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return undefined
  return body as ProblemDetail
}

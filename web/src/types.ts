/**
 * The wire contract with user-account. Mirrors
 * services/user-account/src/main/kotlin/com/wildalert/useraccount/hunter/HunterDtos.kt --
 * if that file changes, this one has to change with it.
 */

/** Body of POST /api/hunters. */
export interface RegisterHunterRequest {
  email: string
  phone: string
  /**
   * 10-72 characters. The upper bound is the server's and is not arbitrary: BCrypt truncates its
   * input at 72 bytes, so a longer password would have characters that do nothing. See
   * PASSWORD_MAX_LENGTH in HunterDtos.kt.
   */
  password: string
}

/** Body of POST /api/sessions. */
export interface LoginRequest {
  email: string
  password: string
}

/** Response from POST /api/hunters (201), POST /api/sessions (200) and GET /api/me (200). */
export interface HunterResponse {
  id: string
  email: string
  phone: string
  active: boolean
  /** Where the hunter forwards trail-cam mail: `<token>@<domain>`. The point of the whole screen. */
  inboundAddress: string
  /**
   * Remaining credit in CZK minor units (haléře) — 2500 is the signup grant. Minor units, not a
   * formatted string, so the client owns the formatting. Spent per alert since credit slice 3.
   */
  creditBalanceMinor: number
  /**
   * What one alert costs the hunter end to end, in haléře (recognition + SMS). Comes from the
   * server rather than being hardcoded here so the dashboard's "about N alerts left" cannot
   * drift from what is actually charged. See billing.alert-price-minor.
   */
  alertPriceMinor: number
  createdAt: string
  updatedAt: string
}

/**
 * RFC 9457 problem details, Spring's standard error body (see GlobalExceptionHandler.kt).
 * `errors` is our own addition on validation failures: a field -> message map.
 */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  errors?: Record<string, string>
}

/**
 * The form fields a server error can be attributed to.
 *
 * Adding one here is only half the job: `FORM_FIELDS` in problemDetail.ts is the runtime
 * allowlist, and a name missing from it degrades into banner text instead of landing on the field.
 */
export type FormField = 'email' | 'phone' | 'password'

export type FieldErrors = Partial<Record<FormField, string>>

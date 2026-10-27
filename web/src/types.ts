/**
 * The wire contract with user-account. Mirrors
 * services/user-account/src/main/kotlin/com/wildalert/useraccount/hunter/HunterDtos.kt --
 * if that file changes, this one has to change with it.
 */

/** Body of POST /api/hunters. */
export interface RegisterHunterRequest {
  email: string
  phone: string
}

/** 201 response from POST /api/hunters. */
export interface HunterResponse {
  id: string
  email: string
  phone: string
  active: boolean
  /** Where the hunter forwards trail-cam mail: `<token>@<domain>`. The point of the whole screen. */
  inboundAddress: string
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

/** The form fields a server error can be attributed to. */
export type FormField = 'email' | 'phone'

export type FieldErrors = Partial<Record<FormField, string>>

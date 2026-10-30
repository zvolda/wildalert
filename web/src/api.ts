import { parseProblem, type ParsedProblem } from './problemDetail'
import type { HunterResponse, LoginRequest, RegisterHunterRequest } from './types'

/** A failure we can explain to the user, with anything field-specific already attributed. */
export class ApiError extends Error {
  constructor(readonly problem: ParsedProblem) {
    super(problem.message || 'Something went wrong')
    this.name = 'ApiError'
  }
}

/**
 * The wire layer. Every URL is relative on purpose: in development Vite's proxy forwards to
 * user-account on :8081, and in production the gateway serves this app and the API from one
 * origin. Either way the browser only ever sees its own origin, so there is no CORS and no base
 * URL to configure.
 *
 * Nothing here touches the session cookie. It is HttpOnly, so script cannot read it even if it
 * wanted to -- the browser attaches it to these same-origin requests by itself.
 */

/** Register, which also logs the hunter in: the 201 carries the session cookie. */
export async function registerHunter(request: RegisterHunterRequest): Promise<HunterResponse> {
  return send('/api/hunters', { method: 'POST', json: request })
}

/** Log in. The 200 carries both the hunter and the session cookie. */
export async function login(request: LoginRequest): Promise<HunterResponse> {
  return send('/api/sessions', { method: 'POST', json: request })
}

/** Log out. Deletes the session server-side and clears the cookie. */
export async function logout(): Promise<void> {
  await send<void>('/api/sessions', { method: 'DELETE', expectBody: false })
}

/**
 * Who is signed in, or null.
 *
 * A 401 is the *expected* answer for a visitor with no cookie, so it resolves to null rather than
 * throwing. Treating "not signed in" as an error would mean every first page load ran through the
 * error path, and the app could not tell a visitor apart from a real failure.
 */
export async function fetchMe(): Promise<HunterResponse | null> {
  try {
    return await send<HunterResponse>('/api/me', { method: 'GET' })
  } catch (error) {
    if (error instanceof ApiError && error.problem.status === 401) return null
    throw error
  }
}

interface SendOptions {
  method: 'GET' | 'POST' | 'DELETE'
  json?: unknown
  /** False for 204 responses, which have no body to parse. */
  expectBody?: boolean
}

async function send<T>(path: string, options: SendOptions): Promise<T> {
  const { method, json, expectBody = true } = options
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers: json === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: json === undefined ? undefined : JSON.stringify(json),
    })
  } catch {
    // fetch only rejects for transport-level failures. During development that is almost always
    // the same cause, so name it instead of saying "network error".
    throw new ApiError({
      message:
        'Could not reach the server. Is user-account running on port 8081? ' +
        '(docker compose up -d postgres, then ./gradlew :services:user-account:bootRun)',
      fieldErrors: {},
    })
  }

  if (!response.ok) {
    throw new ApiError(parseProblem(response.status, await readBody(response)))
  }

  return (expectBody ? ((await response.json()) as T) : (undefined as T))
}

/** Reads the body as JSON when it is JSON, and returns undefined rather than throwing. */
async function readBody(response: Response): Promise<unknown> {
  const text = await response.text().catch(() => '')
  if (text === '') return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

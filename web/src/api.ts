import { parseProblem, type ParsedProblem } from './problemDetail'
import type { HunterResponse, RegisterHunterRequest } from './types'

/** A failure we can explain to the user, with anything field-specific already attributed. */
export class RegistrationError extends Error {
  constructor(readonly problem: ParsedProblem) {
    super(problem.message || 'Registration failed')
    this.name = 'RegistrationError'
  }
}

/**
 * POST /api/hunters.
 *
 * The URL is relative on purpose. In development Vite's proxy forwards it to user-account on
 * :8081; in production the gateway will serve this app and the API from one origin. Either way
 * the browser only ever sees its own origin, so there is no CORS and no base URL to configure.
 */
export async function registerHunter(request: RegisterHunterRequest): Promise<HunterResponse> {
  let response: Response
  try {
    response = await fetch('/api/hunters', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    })
  } catch {
    // fetch only rejects for transport-level failures. During development that is almost always
    // the same cause, so name it instead of saying "network error".
    throw new RegistrationError({
      message:
        'Could not reach the server. Is user-account running on port 8081? ' +
        '(docker compose up -d postgres, then ./gradlew :services:user-account:bootRun)',
      fieldErrors: {},
    })
  }

  if (response.ok) {
    return (await response.json()) as HunterResponse
  }

  throw new RegistrationError(parseProblem(response.status, await readBody(response)))
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

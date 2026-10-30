import { useState, type FormEvent } from 'react'
import { ApiError, login } from './api'
import Field, { cardClass, inputClass, submitButtonClass } from './Field'
import { hasErrors, loginForSubmission, validateLogin } from './validation'
import { type FieldErrors, type HunterResponse } from './types'

interface Props {
  onSignedIn: (hunter: HunterResponse) => void
  onSwitchToRegister: () => void
  onForgotPassword: () => void
}

export default function LoginForm({ onSignedIn, onSwitchToRegister, onForgotPassword }: Props) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [banner, setBanner] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const request = loginForSubmission({ email, password })

    const clientErrors = validateLogin(request)
    if (hasErrors(clientErrors)) {
      setFieldErrors(clientErrors)
      setBanner('')
      return
    }

    setSubmitting(true)
    setFieldErrors({})
    setBanner('')
    try {
      onSignedIn(await login(request))
    } catch (error) {
      if (error instanceof ApiError) {
        // A 401 arrives as a banner, not a field error -- the server deliberately will not say
        // which of the two was wrong, so neither can we. See parseProblem.
        setFieldErrors(error.problem.fieldErrors)
        setBanner(error.problem.message)
      } else {
        setBanner('Something went wrong. Please try again.')
      }
      // The password is cleared on failure; the email is not. Retyping an email you got right is
      // pure friction, whereas a password that was just refused is the thing to try again.
      setPassword('')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate className={cardClass}>
      <h2 className="text-lg font-medium">Sign in</h2>
      <p className="mt-1 mb-6 text-sm text-stone-600 dark:text-stone-400">
        See your credit and the address to send photos to.
      </p>

      {banner && (
        <p
          role="alert"
          className="mb-5 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800 dark:border-red-900 dark:bg-red-950/60 dark:text-red-200"
        >
          {banner}
        </p>
      )}

      <div className="grid gap-5">
        <Field
          id="login-email"
          label="Email address"
          hint="The address you registered with."
          error={fieldErrors.email}
        >
          {(describedBy, invalid) => (
            <input
              id="login-email"
              name="email"
              type="email"
              autoComplete="email"
              autoFocus
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              aria-invalid={invalid}
              aria-describedby={describedBy}
              className={inputClass(invalid)}
              placeholder="you@example.com"
            />
          )}
        </Field>

        <Field
          id="login-password"
          label="Password"
          hint="Forgotten it? There's a link below."
          error={fieldErrors.password}
        >
          {(describedBy, invalid) => (
            <input
              id="login-password"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-invalid={invalid}
              aria-describedby={describedBy}
              className={inputClass(invalid)}
            />
          )}
        </Field>

        <button type="submit" disabled={submitting} className={submitButtonClass}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </div>

      <p className="mt-5 text-sm text-stone-600 dark:text-stone-400">
        <button
          type="button"
          onClick={onForgotPassword}
          className="font-medium text-moss-700 underline underline-offset-2 hover:text-moss-800 dark:text-moss-400"
        >
          Forgot your password?
        </button>
      </p>

      <p className="mt-2 text-sm text-stone-600 dark:text-stone-400">
        No account yet?{' '}
        <button
          type="button"
          onClick={onSwitchToRegister}
          className="font-medium text-moss-700 underline underline-offset-2 hover:text-moss-800 dark:text-moss-400"
        >
          Create one
        </button>
      </p>
    </form>
  )
}

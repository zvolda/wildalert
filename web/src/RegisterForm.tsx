import { useState, type FormEvent } from 'react'
import { ApiError, registerHunter } from './api'
import Field, { cardClass, inputClass, submitButtonClass } from './Field'
import { forSubmission, hasErrors, validateRegistration } from './validation'
import { type FieldErrors, type HunterResponse } from './types'

interface Props {
  onRegistered: (hunter: HunterResponse) => void
  onSwitchToLogin: () => void
}

export default function RegisterForm({ onRegistered, onSwitchToLogin }: Props) {
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [banner, setBanner] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    // Trimmed before both validating and sending, so the client's verdict and the server's
    // cannot disagree over whitespace -- except the password, which is never trimmed. See
    // forSubmission.
    const request = forSubmission({ email, phone, password })

    const clientErrors = validateRegistration(request)
    if (hasErrors(clientErrors)) {
      setFieldErrors(clientErrors)
      setBanner('')
      return
    }

    setSubmitting(true)
    setFieldErrors({})
    setBanner('')
    try {
      onRegistered(await registerHunter(request))
    } catch (error) {
      // Whatever went wrong, the typed-in values stay put: nothing is more annoying than a
      // form that empties itself on a rejected submit.
      if (error instanceof ApiError) {
        setFieldErrors(error.problem.fieldErrors)
        setBanner(error.problem.message)
      } else {
        setBanner('Something went wrong. Please try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      // Our own messages, not the browser's -- they match the server's rules exactly.
      noValidate
      className={cardClass}
    >
      <h2 className="text-lg font-medium">Create your account</h2>
      <p className="mt-1 mb-6 text-sm text-stone-600 dark:text-stone-400">
        We need an email to recognise your camera's mail, and a number to text.
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
          id="email"
          label="Email address"
          hint="The address you'll forward trail-cam emails from."
          error={fieldErrors.email}
        >
          {(describedBy, invalid) => (
            <input
              id="email"
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
          id="phone"
          label="Mobile number"
          hint="International format, including the country code."
          error={fieldErrors.phone}
        >
          {(describedBy, invalid) => (
            <input
              id="phone"
              name="phone"
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              aria-invalid={invalid}
              aria-describedby={describedBy}
              className={inputClass(invalid)}
              placeholder="+420123456789"
            />
          )}
        </Field>

        <Field
          id="password"
          label="Password"
          hint="At least 10 characters. You'll use it to sign back in and check your credit."
          error={fieldErrors.password}
        >
          {(describedBy, invalid) => (
            <input
              id="password"
              name="password"
              type="password"
              // new-password, so a password manager offers to generate and save one rather than
              // autofilling an existing credential into a registration form.
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-invalid={invalid}
              aria-describedby={describedBy}
              className={inputClass(invalid)}
            />
          )}
        </Field>

        <button type="submit" disabled={submitting} className={submitButtonClass}>
          {submitting ? 'Creating your account…' : 'Create account'}
        </button>
      </div>

      <p className="mt-5 text-sm text-stone-600 dark:text-stone-400">
        Already registered?{' '}
        <button
          type="button"
          onClick={onSwitchToLogin}
          className="font-medium text-moss-700 underline underline-offset-2 hover:text-moss-800 dark:text-moss-400"
        >
          Sign in
        </button>
      </p>
    </form>
  )
}

import { useState, type FormEvent } from 'react'
import { ApiError, requestPasswordReset } from './api'
import Field, { cardClass, inputClass, submitButtonClass } from './Field'
import { hasErrors, validatePasswordResetRequest } from './validation'
import type { FieldErrors } from './types'

interface Props {
  onBackToLogin: () => void
}

/**
 * "I forgot my password": one address in, one unchanging confirmation out.
 *
 * **The confirmation is shown whether or not the address has an account, and that is the
 * requirement rather than a simplification.** user-account answers 202 to every well-formed
 * address precisely so this form cannot be used to discover who is registered; wording it as
 * "we've sent you a link" would quietly undo that by making the two cases look different the
 * moment anyone compares. So the copy says what was done with the request, not what happened to
 * it -- "if … has an account".
 */
export default function ForgotPasswordForm({ onBackToLogin }: Props) {
  const [email, setEmail] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [banner, setBanner] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submittedTo, setSubmittedTo] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmed = email.trim()

    const clientErrors = validatePasswordResetRequest({ email: trimmed })
    if (hasErrors(clientErrors)) {
      setFieldErrors(clientErrors)
      setBanner('')
      return
    }

    setSubmitting(true)
    setFieldErrors({})
    setBanner('')
    try {
      await requestPasswordReset({ email: trimmed })
      setSubmittedTo(trimmed)
    } catch (error) {
      // Reaching here means the request itself failed -- a 400 on the address format, or the
      // server being unreachable. It does *not* mean "no such account", which is unreachable by
      // design and must never be reported.
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

  if (submittedTo !== null) {
    return (
      <section className={cardClass} aria-labelledby="reset-sent-heading">
        <h2 id="reset-sent-heading" className="text-lg font-medium">
          Check your email
        </h2>
        <p className="mt-2 text-sm text-stone-600 dark:text-stone-400">
          If <span className="font-medium text-stone-800 dark:text-stone-200">{submittedTo}</span>{' '}
          has a WildAlert account, a link to choose a new password is on its way. It works once and
          expires in an hour.
        </p>
        <p className="mt-3 text-sm text-stone-600 dark:text-stone-400">
          Nothing arrived? Check the spam folder, then try again in a few minutes — we only send
          one link at a time.
        </p>
        <button
          type="button"
          onClick={onBackToLogin}
          className="mt-5 font-medium text-moss-700 underline underline-offset-2 hover:text-moss-800 dark:text-moss-400"
        >
          Back to sign in
        </button>
      </section>
    )
  }

  return (
    <form onSubmit={handleSubmit} noValidate className={cardClass}>
      <h2 className="text-lg font-medium">Reset your password</h2>
      <p className="mt-1 mb-6 text-sm text-stone-600 dark:text-stone-400">
        We'll email you a link to choose a new one.
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
          id="forgot-email"
          label="Email address"
          hint="The address you registered with."
          error={fieldErrors.email}
        >
          {(describedBy, invalid) => (
            <input
              id="forgot-email"
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

        <button type="submit" disabled={submitting} className={submitButtonClass}>
          {submitting ? 'Sending…' : 'Email me a link'}
        </button>
      </div>

      <p className="mt-5 text-sm text-stone-600 dark:text-stone-400">
        Remembered it?{' '}
        <button
          type="button"
          onClick={onBackToLogin}
          className="font-medium text-moss-700 underline underline-offset-2 hover:text-moss-800 dark:text-moss-400"
        >
          Sign in
        </button>
      </p>
    </form>
  )
}

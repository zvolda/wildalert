import { useState, type FormEvent } from 'react'
import { ApiError, confirmPasswordReset } from './api'
import Field, { cardClass, inputClass, submitButtonClass } from './Field'
import { hasErrors, validateConfirmPasswordReset } from './validation'
import type { FieldErrors } from './types'

interface Props {
  /** Straight out of `?token=` — opaque, never displayed, never validated here. */
  token: string
  /** Called once the password is changed, to send them to the sign-in form. */
  onReset: () => void
}

/**
 * The screen the emailed link opens: choose a new password.
 *
 * **It does not sign the hunter in.** The server issues no cookie here, which is deliberate --
 * reading the mailbox proves access to the mailbox, not knowledge of the password. Sending them to
 * sign in means the new password is used once immediately, so a typo surfaces now rather than
 * tomorrow.
 *
 * The token is not shown, not editable and not checked client-side: it was not typed by the
 * hunter, so there is nothing useful to say about it that the server's one generic 400 does not
 * already say better.
 */
export default function ResetPasswordForm({ token, onReset }: Props) {
  const [newPassword, setNewPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [banner, setBanner] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const clientErrors = validateConfirmPasswordReset(newPassword)
    if (hasErrors(clientErrors)) {
      setFieldErrors(clientErrors)
      setBanner('')
      return
    }

    setSubmitting(true)
    setFieldErrors({})
    setBanner('')
    try {
      await confirmPasswordReset({ token, newPassword })
      setDone(true)
    } catch (error) {
      if (error instanceof ApiError) {
        setFieldErrors(error.problem.fieldErrors)
        // A dead link arrives as a banner: it is nothing to do with the password they just typed,
        // and attaching it to that field would send them off rewriting a password that was fine.
        setBanner(error.problem.message)
      } else {
        setBanner('Something went wrong. Please try again.')
      }
      setNewPassword('')
    } finally {
      setSubmitting(false)
    }
  }

  if (done) {
    return (
      <section className={cardClass} aria-labelledby="reset-done-heading">
        <h2 id="reset-done-heading" className="text-lg font-medium">
          Password changed
        </h2>
        <p className="mt-2 text-sm text-stone-600 dark:text-stone-400">
          You can sign in with your new password now. Anyone who was signed in to your account —
          on any device — has been signed out.
        </p>
        <button type="button" onClick={onReset} className={`${submitButtonClass} mt-5 block`}>
          Sign in
        </button>
      </section>
    )
  }

  return (
    <form onSubmit={handleSubmit} noValidate className={cardClass}>
      <h2 className="text-lg font-medium">Choose a new password</h2>
      <p className="mt-1 mb-6 text-sm text-stone-600 dark:text-stone-400">
        This link works once. Setting a password here signs out every device.
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
          id="reset-password"
          label="New password"
          hint="At least 10 characters."
          error={fieldErrors.newPassword}
        >
          {(describedBy, invalid) => (
            <input
              id="reset-password"
              name="newPassword"
              type="password"
              autoComplete="new-password"
              autoFocus
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              aria-invalid={invalid}
              aria-describedby={describedBy}
              className={inputClass(invalid)}
            />
          )}
        </Field>

        <button type="submit" disabled={submitting} className={submitButtonClass}>
          {submitting ? 'Saving…' : 'Set new password'}
        </button>
      </div>
    </form>
  )
}

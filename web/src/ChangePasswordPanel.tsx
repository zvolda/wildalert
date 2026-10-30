import { useState, type FormEvent } from 'react'
import { ApiError, changePassword } from './api'
import Field, { cardClass, inputClass, submitButtonClass } from './Field'
import { hasErrors, validateChangePassword } from './validation'
import type { FieldErrors, HunterResponse } from './types'

interface Props {
  /** Handed the refreshed hunter, so the dashboard above stays in step. */
  onChanged: (hunter: HunterResponse) => void
}

/**
 * Change your password from the dashboard. Collapsed until asked for, because it is the rarest
 * thing on the page and the two things a hunter actually comes for are credit and the address.
 *
 * **Needs no new infrastructure at all** -- no email, no provider, no DNS -- which is why it
 * shipped alongside the forgotten-password flow rather than after it: a hunter who merely
 * *suspects* their password has leaked can act today, and that is the case the reset flow does
 * not cover.
 */
export default function ChangePasswordPanel({ onChanged }: Props) {
  const [open, setOpen] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [banner, setBanner] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)

  function reset() {
    setCurrentPassword('')
    setNewPassword('')
    setFieldErrors({})
    setBanner('')
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const request = { currentPassword, newPassword }

    const clientErrors = validateChangePassword(request)
    if (hasErrors(clientErrors)) {
      setFieldErrors(clientErrors)
      setBanner('')
      return
    }

    setSubmitting(true)
    setFieldErrors({})
    setBanner('')
    try {
      const hunter = await changePassword(request)
      // The response carries a fresh cookie, so this browser stays signed in. Collapse the panel
      // and say what happened to the *other* devices, which is the part worth confirming.
      reset()
      setOpen(false)
      setDone(true)
      onChanged(hunter)
    } catch (error) {
      if (error instanceof ApiError) {
        setFieldErrors(error.problem.fieldErrors)
        // A 401 here means the current password was wrong. It arrives as a banner because the
        // server will not say which value it objected to -- the same rule as the login form.
        setBanner(error.problem.message)
      } else {
        setBanner('Something went wrong. Please try again.')
      }
      // Clear both: the one that was refused, and the new one, which should not sit in the form
      // after a failure anyone else could walk up to.
      setCurrentPassword('')
      setNewPassword('')
    } finally {
      setSubmitting(false)
    }
  }

  if (!open) {
    return (
      <section className={cardClass} aria-labelledby="password-heading">
        <h2
          id="password-heading"
          className="text-sm font-medium text-stone-600 dark:text-stone-400"
        >
          Password
        </h2>
        {done ? (
          <p role="status" className="mt-2 text-sm text-stone-600 dark:text-stone-400">
            Password changed. Every other device has been signed out.
          </p>
        ) : (
          <p className="mt-2 text-sm text-stone-600 dark:text-stone-400">
            Changing it signs out every other device.
          </p>
        )}
        <button
          type="button"
          onClick={() => {
            setDone(false)
            setOpen(true)
          }}
          className="mt-3 rounded-lg border border-stone-300 px-3 py-1.5 text-sm font-medium text-stone-700 transition hover:bg-stone-100 dark:border-stone-700 dark:text-stone-300 dark:hover:bg-stone-800"
        >
          Change password
        </button>
      </section>
    )
  }

  return (
    <form onSubmit={handleSubmit} noValidate className={cardClass}>
      <h2 className="text-lg font-medium">Change password</h2>
      <p className="mt-1 mb-6 text-sm text-stone-600 dark:text-stone-400">
        You'll stay signed in here. Every other device will be signed out.
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
          id="current-password"
          label="Current password"
          hint="Asked for even though you are signed in — this page can lock you out."
          error={fieldErrors.currentPassword}
        >
          {(describedBy, invalid) => (
            <input
              id="current-password"
              name="currentPassword"
              type="password"
              autoComplete="current-password"
              autoFocus
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              aria-invalid={invalid}
              aria-describedby={describedBy}
              className={inputClass(invalid)}
            />
          )}
        </Field>

        <Field
          id="new-password"
          label="New password"
          hint="At least 10 characters."
          error={fieldErrors.newPassword}
        >
          {(describedBy, invalid) => (
            <input
              id="new-password"
              name="newPassword"
              type="password"
              autoComplete="new-password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              aria-invalid={invalid}
              aria-describedby={describedBy}
              className={inputClass(invalid)}
            />
          )}
        </Field>

        <div className="flex items-center gap-3">
          <button type="submit" disabled={submitting} className={submitButtonClass}>
            {submitting ? 'Saving…' : 'Save new password'}
          </button>
          <button
            type="button"
            onClick={() => {
              reset()
              setOpen(false)
            }}
            className="mt-1 rounded-lg border border-stone-300 px-3 py-2.5 text-sm font-medium text-stone-700 transition hover:bg-stone-100 dark:border-stone-700 dark:text-stone-300 dark:hover:bg-stone-800"
          >
            Cancel
          </button>
        </div>
      </div>
    </form>
  )
}

import { useState, type FormEvent, type ReactNode } from 'react'
import { RegistrationError, registerHunter } from './api'
import { forSubmission, hasErrors, validateRegistration } from './validation'
import { PLANS, type FieldErrors, type HunterResponse, type Plan } from './types'

interface Props {
  onRegistered: (hunter: HunterResponse) => void
}

const PLAN_LABELS: Record<Plan, string> = {
  FREE: 'Free',
  BASIC: 'Basic',
  PRO: 'Pro',
}

export default function RegisterForm({ onRegistered }: Props) {
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [plan, setPlan] = useState<Plan>('FREE')
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [banner, setBanner] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    // Trimmed before both validating and sending, so the client's verdict and the server's
    // cannot disagree over whitespace. See forSubmission.
    const request = forSubmission({ email, phone, plan })

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
      if (error instanceof RegistrationError) {
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
      className="rounded-xl border border-stone-200 bg-white p-6 shadow-sm dark:border-stone-800 dark:bg-stone-900"
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

        <fieldset>
          <legend className="text-sm font-medium">Plan</legend>
          <div className="mt-2 flex gap-2">
            {PLANS.map((option) => (
              <label
                key={option}
                className={
                  'flex-1 cursor-pointer rounded-lg border px-3 py-2 text-center text-sm transition ' +
                  (plan === option
                    ? 'border-moss-600 bg-moss-50 font-medium text-moss-800 dark:border-moss-400 dark:bg-moss-950 dark:text-moss-100'
                    : 'border-stone-300 hover:border-stone-400 dark:border-stone-700 dark:hover:border-stone-600')
                }
              >
                <input
                  type="radio"
                  name="plan"
                  value={option}
                  checked={plan === option}
                  onChange={() => setPlan(option)}
                  className="sr-only"
                />
                {PLAN_LABELS[option]}
              </label>
            ))}
          </div>
        </fieldset>

        <button
          type="submit"
          disabled={submitting}
          className="mt-1 rounded-lg bg-moss-700 px-4 py-2.5 font-medium text-white transition hover:bg-moss-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-moss-700 disabled:cursor-not-allowed disabled:opacity-60 dark:bg-moss-600 dark:hover:bg-moss-700"
        >
          {submitting ? 'Creating your account…' : 'Create account'}
        </button>
      </div>
    </form>
  )
}

function inputClass(invalid: boolean): string {
  return (
    'w-full rounded-lg border bg-white px-3 py-2 text-sm outline-none transition ' +
    'focus:ring-2 focus:ring-moss-600/40 dark:bg-stone-950 ' +
    (invalid
      ? 'border-red-400 focus:border-red-500 dark:border-red-700'
      : 'border-stone-300 focus:border-moss-600 dark:border-stone-700')
  )
}

interface FieldProps {
  id: string
  label: string
  hint: string
  error: string | undefined
  /** Given the ids to reference and whether the field is in error, render the control. */
  children: (describedBy: string, invalid: boolean) => ReactNode
}

/**
 * A label, a control, a hint and an error message, wired together by id. Screen readers need
 * aria-describedby to point at whichever of the two texts is actually on screen, which is the
 * fiddly part worth doing once here rather than per field.
 */
function Field({ id, label, hint, error, children }: FieldProps) {
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const invalid = error !== undefined

  return (
    <div className="grid gap-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children(invalid ? errorId : hintId, invalid)}
      {invalid ? (
        <p id={errorId} className="text-sm text-red-700 dark:text-red-400">
          {error}
        </p>
      ) : (
        <p id={hintId} className="text-xs text-stone-500 dark:text-stone-500">
          {hint}
        </p>
      )}
    </div>
  )
}

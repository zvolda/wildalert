import type { ReactNode } from 'react'

/**
 * A label, a control, a hint and an error message, wired together by id.
 *
 * Lived inside RegisterForm until LoginForm needed the same thing. Moved rather than copied:
 * the fiddly part is the aria wiring, and two divergent copies of that is two sets of
 * accessibility bugs.
 */
interface FieldProps {
  id: string
  label: string
  hint: string
  error: string | undefined
  /** Given the ids to reference and whether the field is in error, render the control. */
  children: (describedBy: string, invalid: boolean) => ReactNode
}

export default function Field({ id, label, hint, error, children }: FieldProps) {
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const invalid = error !== undefined

  return (
    <div className="grid gap-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {/* Screen readers need aria-describedby to point at whichever of the two texts is actually
          on screen, which is the part worth doing once here rather than per field. */}
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

export function inputClass(invalid: boolean): string {
  return (
    'w-full rounded-lg border bg-white px-3 py-2 text-sm outline-none transition ' +
    'focus:ring-2 focus:ring-moss-600/40 dark:bg-stone-950 ' +
    (invalid
      ? 'border-red-400 focus:border-red-500 dark:border-red-700'
      : 'border-stone-300 focus:border-moss-600 dark:border-stone-700')
  )
}

/** The primary action button, shared so the two forms cannot drift apart visually. */
export const submitButtonClass =
  'mt-1 rounded-lg bg-moss-700 px-4 py-2.5 font-medium text-white transition hover:bg-moss-800 ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-moss-700 ' +
  'disabled:cursor-not-allowed disabled:opacity-60 dark:bg-moss-600 dark:hover:bg-moss-700'

/** The card every panel and form sits in. */
export const cardClass =
  'rounded-xl border border-stone-200 bg-white p-6 shadow-sm dark:border-stone-800 dark:bg-stone-900'

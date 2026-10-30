import { useState } from 'react'
import { ApiError, logout } from './api'
import ChangePasswordPanel from './ChangePasswordPanel'
import { alertsLabel, alertsRemaining, formatCredit } from './credit'
import { cardClass } from './Field'
import InboundAddressPanel from './InboundAddressPanel'
import type { HunterResponse } from './types'

interface Props {
  hunter: HunterResponse
  /** True when this hunter has only just registered, which changes the copy and the focus. */
  justRegistered: boolean
  onSignedOut: () => void
  /** Called with the refreshed hunter after a password change, so this view stays in step. */
  onHunterChanged: (hunter: HunterResponse) => void
}

/**
 * What a signed-in hunter sees: how much credit is left, and where to send photos.
 *
 * Those two in that order on purpose. The address is the thing a *new* hunter needs, which is why
 * registering still leads with it -- but a hunter who signs in later is almost always answering
 * "have I still got credit?", and the address is a reminder rather than news.
 */
export default function Dashboard({
  hunter,
  justRegistered,
  onSignedOut,
  onHunterChanged,
}: Props) {
  return (
    <div className="grid gap-5">
      {justRegistered ? (
        <>
          <InboundAddressPanel hunter={hunter} moveFocusOnMount />
          <CreditPanel hunter={hunter} />
        </>
      ) : (
        <>
          <CreditPanel hunter={hunter} />
          <InboundAddressPanel
            hunter={hunter}
            title="Where to send photos"
            subtitle={`Trail-cam photos sent here get a text back to ${hunter.phone}.`}
          />
        </>
      )}
      {/* Last of the three panels, and below the address rather than above it: changing a
          password is the rarest reason to be on this page. */}
      <ChangePasswordPanel onChanged={onHunterChanged} />
      <SignOut onSignedOut={onSignedOut} />
    </div>
  )
}

function CreditPanel({ hunter }: { hunter: HunterResponse }) {
  const alerts = alertsRemaining(hunter.creditBalanceMinor, hunter.alertPriceMinor)
  const empty = hunter.creditBalanceMinor <= 0
  // One alert's worth or less. Matches the server's LOW_BALANCE spirit without duplicating its
  // threshold: what a hunter cares about is "can this still send a text?".
  const low = !empty && alerts <= 1

  return (
    <section className={cardClass} aria-labelledby="credit-heading">
      <h2 id="credit-heading" className="text-sm font-medium text-stone-600 dark:text-stone-400">
        Your credit
      </h2>
      <p className="mt-1 text-3xl font-semibold tracking-tight tabular-nums">
        {formatCredit(hunter.creditBalanceMinor)}
      </p>

      {empty ? (
        // The honest message for the state production is actually in: the signup grant is 0
        // until there is abuse protection in front of the form, so a hunter who registers today
        // sees exactly this. Saying nothing would make a working page look broken.
        <p className="mt-2 text-sm text-stone-600 dark:text-stone-400">
          No credit yet, so no alerts will be sent. Top-ups are coming — get in touch in the
          meantime and we'll sort you out.
        </p>
      ) : (
        <p className="mt-2 text-sm text-stone-600 dark:text-stone-400">
          About <span className="font-medium text-stone-800 dark:text-stone-200">
            {alertsLabel(alerts)}
          </span>{' '}
          left, at {formatCredit(hunter.alertPriceMinor)} per recognised photo.
        </p>
      )}

      {low && (
        <p
          role="status"
          className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-900 dark:bg-amber-950/50 dark:text-amber-100"
        >
          Running low — you'll stop getting texts when this reaches zero.
        </p>
      )}
    </section>
  )
}

function SignOut({ onSignedOut }: { onSignedOut: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function handleClick() {
    setBusy(true)
    setError('')
    try {
      await logout()
      onSignedOut()
    } catch (err) {
      // A failed logout must not pretend to have worked: the session is still live server-side,
      // and showing the anonymous screen would leave a signed-in cookie behind a page that
      // claims otherwise.
      setError(err instanceof ApiError ? err.message : 'Could not sign out. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex items-center justify-end gap-3">
      {error && (
        <p role="alert" className="text-sm text-red-700 dark:text-red-400">
          {error}
        </p>
      )}
      <button
        type="button"
        onClick={handleClick}
        disabled={busy}
        className="rounded-lg border border-stone-300 px-3 py-1.5 text-sm font-medium text-stone-700 transition hover:bg-stone-100 disabled:opacity-60 dark:border-stone-700 dark:text-stone-300 dark:hover:bg-stone-800"
      >
        {busy ? 'Signing out…' : 'Sign out'}
      </button>
    </div>
  )
}

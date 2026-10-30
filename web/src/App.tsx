import { useEffect, useState } from 'react'
import { fetchMe } from './api'
import Dashboard from './Dashboard'
import ForgotPasswordForm from './ForgotPasswordForm'
import LoginForm from './LoginForm'
import RegisterForm from './RegisterForm'
import ResetPasswordForm from './ResetPasswordForm'
import type { HunterResponse } from './types'

/**
 * The whole app: a signed-in hunter sees their dashboard, everyone else registers or signs in.
 *
 * **Still no router, and that is now a decision rather than a postponement.** The session cookie
 * does the job a URL would: on load the app asks `GET /api/me`, and a hunter who refreshes,
 * reopens the tab or comes back tomorrow lands straight on their dashboard. Adding real routes
 * would buy a bookmarkable `/login` and cost a new dependency (`npm install` does not work on
 * this network, see AGENTS.md) plus an SPA fallback in the gateway, which does not exist -- any
 * unknown path there is a 404 today. Worth revisiting when there are enough screens to get lost
 * between.
 */
type Screen = 'register' | 'login' | 'forgot'

/**
 * The reset token out of `?reset=…`, read once at module load.
 *
 * **A query parameter rather than a `/reset` path, deliberately** -- and it is the same decision
 * as "no router", not a separate one. The gateway serves the built app from `static-locations`
 * with no SPA fallback, so a path-shaped link would 404 before React ever ran, and the emailed
 * link is the one URL that absolutely cannot be broken. `PasswordResetService` builds it to match.
 */
function resetTokenFromUrl(): string | null {
  try {
    const token = new URLSearchParams(window.location.search).get('reset')
    return token !== null && token.trim() !== '' ? token : null
  } catch {
    // URLSearchParams is everywhere, but this runs before anything else can report a problem,
    // and a throw here would blank the page for every visitor rather than just reset arrivals.
    return null
  }
}

/**
 * Rewrites the address bar back to the bare origin once a token has been spent.
 *
 * `replaceState` rather than a navigation: there is nothing to reload, and pushing a new entry
 * would leave the dead link one Back away. Guarded because `history` is unavailable in some
 * embedded webviews, and failing to tidy the URL must not break the page that just worked.
 */
function clearResetTokenFromUrl(): void {
  try {
    window.history.replaceState(null, '', window.location.pathname)
  } catch {
    // Nothing to do. The token is already spent and will be refused if the URL is revisited.
  }
}

export default function App() {
  const [hunter, setHunter] = useState<HunterResponse | null>(null)
  const [justRegistered, setJustRegistered] = useState(false)
  const [screen, setScreen] = useState<Screen>('register')
  const [resetToken, setResetToken] = useState<string | null>(resetTokenFromUrl)
  // Starts true: until /api/me answers we genuinely do not know, and rendering the registration
  // form in the meantime would flash a signed-out page at a signed-in hunter on every load.
  const [checkingSession, setCheckingSession] = useState(true)

  useEffect(() => {
    let cancelled = false
    fetchMe()
      .then((me) => {
        if (cancelled) return
        if (me) {
          setHunter(me)
          setJustRegistered(false)
        }
      })
      .catch(() => {
        // fetchMe already turns "not signed in" into null, so reaching here means the server is
        // unreachable or broken. The anonymous screen is the right thing to show either way, and
        // the forms will produce a real error message the moment anyone submits.
      })
      .finally(() => {
        if (!cancelled) setCheckingSession(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div className="min-h-dvh px-4 py-10 sm:py-16">
      <main className="mx-auto w-full max-w-lg">
        <header className="mb-8">
          <h1 className="text-2xl font-semibold tracking-tight">WildAlert</h1>
          <p className="mt-1 text-sm text-stone-600 dark:text-stone-400">
            Email a trail-cam photo, get a text telling you what walked past.
          </p>
        </header>

        {resetToken !== null ? (
          // Ahead of the session check on purpose: someone following a reset link is there to
          // reset, and a cookie from an old session -- quite possibly the intruder's, which is
          // why they are resetting -- must not send them to a dashboard instead.
          <ResetPasswordForm
            token={resetToken}
            onReset={() => {
              // Drop the token from the address bar before anything else. It is spent, and
              // leaving it there means a refresh retries a dead link, and the browser history
              // keeps a credential-shaped URL around.
              clearResetTokenFromUrl()
              setResetToken(null)
              setHunter(null)
              setScreen('login')
            }}
          />
        ) : checkingSession ? (
          <p className="text-sm text-stone-500 dark:text-stone-500">Loading…</p>
        ) : hunter ? (
          <Dashboard
            hunter={hunter}
            justRegistered={justRegistered}
            onHunterChanged={(updated) => setHunter(updated)}
            onSignedOut={() => {
              setHunter(null)
              setJustRegistered(false)
              // Back to the sign-in form, not the registration one: somebody who has just signed
              // out has an account.
              setScreen('login')
            }}
          />
        ) : screen === 'register' ? (
          <RegisterForm
            onRegistered={(registered) => {
              // Registering issues the session too, so there is nothing else to do here.
              setHunter(registered)
              setJustRegistered(true)
            }}
            onSwitchToLogin={() => setScreen('login')}
          />
        ) : screen === 'forgot' ? (
          <ForgotPasswordForm onBackToLogin={() => setScreen('login')} />
        ) : (
          <LoginForm
            onSignedIn={(signedIn) => {
              setHunter(signedIn)
              setJustRegistered(false)
            }}
            onSwitchToRegister={() => setScreen('register')}
            onForgotPassword={() => setScreen('forgot')}
          />
        )}

        <footer className="mt-10 text-xs text-stone-500 dark:text-stone-500">
          Recognition runs on DeepFaune, tuned for European wildlife.
        </footer>
      </main>
    </div>
  )
}

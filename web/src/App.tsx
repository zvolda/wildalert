import { useEffect, useState } from 'react'
import { fetchMe } from './api'
import Dashboard from './Dashboard'
import LoginForm from './LoginForm'
import RegisterForm from './RegisterForm'
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
type Screen = 'register' | 'login'

export default function App() {
  const [hunter, setHunter] = useState<HunterResponse | null>(null)
  const [justRegistered, setJustRegistered] = useState(false)
  const [screen, setScreen] = useState<Screen>('register')
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

        {checkingSession ? (
          <p className="text-sm text-stone-500 dark:text-stone-500">Loading…</p>
        ) : hunter ? (
          <Dashboard
            hunter={hunter}
            justRegistered={justRegistered}
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
        ) : (
          <LoginForm
            onSignedIn={(signedIn) => {
              setHunter(signedIn)
              setJustRegistered(false)
            }}
            onSwitchToRegister={() => setScreen('register')}
          />
        )}

        <footer className="mt-10 text-xs text-stone-500 dark:text-stone-500">
          Recognition runs on DeepFaune, tuned for European wildlife.
        </footer>
      </main>
    </div>
  )
}

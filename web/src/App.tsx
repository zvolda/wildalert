import { useState } from 'react'
import InboundAddressPanel from './InboundAddressPanel'
import RegisterForm from './RegisterForm'
import type { HunterResponse } from './types'

/**
 * The whole app, for now: a registration form that becomes a "here is your address" panel.
 *
 * One piece of state decides which of the two is on screen. There is no router yet because
 * there is only one screen -- that arrives with the dashboard.
 */
export default function App() {
  const [hunter, setHunter] = useState<HunterResponse | null>(null)

  return (
    <div className="min-h-dvh px-4 py-10 sm:py-16">
      <main className="mx-auto w-full max-w-lg">
        <header className="mb-8">
          <h1 className="text-2xl font-semibold tracking-tight">WildAlert</h1>
          <p className="mt-1 text-sm text-stone-600 dark:text-stone-400">
            Email a trail-cam photo, get a text telling you what walked past.
          </p>
        </header>

        {hunter ? (
          <InboundAddressPanel hunter={hunter} />
        ) : (
          <RegisterForm onRegistered={setHunter} />
        )}

        <footer className="mt-10 text-xs text-stone-500 dark:text-stone-500">
          Recognition runs on DeepFaune, tuned for European wildlife.
        </footer>
      </main>
    </div>
  )
}

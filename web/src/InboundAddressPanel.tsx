import { useEffect, useRef, useState } from 'react'
import type { HunterResponse } from './types'

interface Props {
  hunter: HunterResponse
  /**
   * Move focus to this panel's heading when it appears.
   *
   * True only right after registering, where the form the hunter was reading has just vanished
   * and nothing else would announce the change. **False on the dashboard**, where this panel is
   * simply part of a page that loaded: taking focus there would yank it away from a returning
   * hunter on every single visit, which is the opposite of helpful.
   */
  moveFocusOnMount?: boolean
  /** Differs between "you're registered" and a returning hunter's dashboard. */
  title?: string
  subtitle?: string
}

/**
 * The payoff screen. Registering is not the point -- knowing where to send photos is, so the
 * address is the largest thing here and everything else is secondary.
 *
 * Every value rendered comes from the response rather than from what was typed, because the
 * server normalises the email (trim + lowercase, see HunterService.normalizeEmail).
 */
export default function InboundAddressPanel({
  hunter,
  moveFocusOnMount = false,
  title = "You're registered",
  subtitle,
}: Props) {
  const [copied, setCopied] = useState(false)
  const heading = useRef<HTMLHeadingElement>(null)

  // After registering, the form that was being read is now gone, and moving focus to this
  // heading tells a screen reader the page changed under it. On the dashboard there was no such
  // change, so taking focus would be an unexplained jump -- hence the flag rather than always.
  useEffect(() => {
    if (moveFocusOnMount) heading.current?.focus()
  }, [moveFocusOnMount])

  // Clear the "copied" acknowledgement again, so it reads as feedback on the last click
  // rather than as a permanent label.
  useEffect(() => {
    if (!copied) return
    const timer = setTimeout(() => setCopied(false), 2500)
    return () => clearTimeout(timer)
  }, [copied])

  async function copy() {
    try {
      await navigator.clipboard.writeText(hunter.inboundAddress)
      setCopied(true)
    } catch {
      // Clipboard access needs a secure context and can be refused outright. The address is
      // selectable text on screen either way, so this is a non-event -- just don't claim success.
      setCopied(false)
    }
  }

  return (
    <div className="rounded-xl border border-moss-200 bg-white p-6 shadow-sm dark:border-moss-800 dark:bg-stone-900">
      <h2
        ref={heading}
        tabIndex={-1}
        className="text-lg font-medium outline-none"
      >
        {title}
      </h2>
      <p className="mt-1 text-sm text-stone-600 dark:text-stone-400">
        {subtitle ?? (
          <>
            Send trail-cam photos to this address and we'll text{' '}
            <span className="font-medium text-stone-800 dark:text-stone-200">{hunter.phone}</span>{' '}
            with what we find.
          </>
        )}
      </p>

      <div className="mt-5 rounded-lg bg-moss-50 p-4 dark:bg-moss-950">
        <p className="text-xs font-medium tracking-wide text-moss-800 uppercase dark:text-moss-200">
          Your personal address
        </p>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <code className="font-mono text-base break-all text-moss-950 sm:text-lg dark:text-moss-50">
            {hunter.inboundAddress}
          </code>
          <button
            type="button"
            onClick={copy}
            className="ml-auto shrink-0 rounded-md border border-moss-600 px-2.5 py-1 text-sm font-medium text-moss-800 transition hover:bg-moss-100 dark:border-moss-400 dark:text-moss-100 dark:hover:bg-moss-900"
          >
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
        <p aria-live="polite" className="sr-only">
          {copied ? 'Address copied to the clipboard' : ''}
        </p>
      </div>

      <details className="mt-5">
        <summary className="cursor-pointer text-sm font-medium text-moss-800 hover:underline dark:text-moss-200">
          How do I send photos there?
        </summary>
        <div className="mt-3 space-y-4 text-sm text-stone-700 dark:text-stone-300">
          <section>
            <h3 className="font-medium">Straight from the camera</h3>
            <p className="mt-1">
              If your camera app lets you set the recipient for photo emails, put the address above
              in it. Nothing else to do.
            </p>
          </section>
          <section>
            <h3 className="font-medium">Or forward from Gmail</h3>
            <ol className="mt-1 list-decimal space-y-1 pl-5">
              <li>
                Settings → <em>See all settings</em> → <em>Forwarding and POP/IMAP</em> →{' '}
                <em>Add a forwarding address</em>, and paste the address above.
              </li>
              <li>Confirm it from the email Gmail sends you.</li>
              <li>
                Then <em>Filters and Blocked Addresses</em> → <em>Create a new filter</em>, put your
                camera's sender address in <em>From</em>, and choose <em>Forward it to</em> your new
                address. Forwarding only the camera's mail keeps the rest of your inbox private.
              </li>
            </ol>
          </section>
          <p className="text-xs text-stone-500 dark:text-stone-500">
            Photos arrive as email attachments; we read the attachment, not the message. Night-time
            black-and-white infrared shots are what the model was trained for.
          </p>
        </div>
      </details>

      <dl className="mt-6 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 border-t border-stone-200 pt-4 text-sm dark:border-stone-800">
        <dt className="text-stone-500 dark:text-stone-500">Email</dt>
        <dd className="break-all">{hunter.email}</dd>
      </dl>
    </div>
  )
}

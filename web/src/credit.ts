/**
 * Turning a balance in haléře into something a hunter can act on.
 *
 * Pure on purpose: `vite.config.ts` runs Vitest in a plain node environment with no jsdom, so
 * pure modules are the only things this project can unit-test. Money arithmetic is also exactly
 * the kind of thing worth testing away from a component.
 */

/** `2500` -> `"25.00 CZK"`. Integer arithmetic throughout -- no float ever touches a balance. */
export function formatCredit(minor: number): string {
  const negative = minor < 0
  const absolute = Math.abs(Math.trunc(minor))
  const units = Math.floor(absolute / 100)
  const hundredths = absolute % 100
  const sign = negative ? '-' : ''
  return `${sign}${units}.${String(hundredths).padStart(2, '0')} CZK`
}

/**
 * How many more alerts the balance buys, rounded down.
 *
 * Rounded down because "about 10 alerts" must never promise an eleventh. Returns 0 rather than
 * Infinity or NaN when the price is missing or nonsensical -- a server that sent a price of 0
 * should not make the page claim unlimited alerts.
 */
export function alertsRemaining(balanceMinor: number, alertPriceMinor: number): number {
  if (!Number.isFinite(alertPriceMinor) || alertPriceMinor <= 0) return 0
  if (!Number.isFinite(balanceMinor) || balanceMinor <= 0) return 0
  return Math.floor(balanceMinor / alertPriceMinor)
}

/** `1` -> `"1 alert"`, anything else -> `"N alerts"`. */
export function alertsLabel(count: number): string {
  return count === 1 ? '1 alert' : `${count} alerts`
}

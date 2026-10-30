import { describe, expect, it } from 'vitest'
import { alertsLabel, alertsRemaining, formatCredit } from '../credit'

describe('formatCredit', () => {
  it('formats the signup grant the way the dashboard shows it', () => {
    // 2500 haléře is the SIGNUP_GRANT_MINOR value in compose.yml.
    expect(formatCredit(2500)).toBe('25.00 CZK')
  })

  it('pads the haléře so the decimals never collapse', () => {
    // 2050 is a real balance from credit slice 3's live run: 2200 minus one 150 debit.
    expect(formatCredit(2050)).toBe('20.50 CZK')
    expect(formatCredit(2005)).toBe('20.05 CZK')
    expect(formatCredit(2000)).toBe('20.00 CZK')
  })

  it('formats a balance under one crown', () => {
    // 100 is where slice 3's live run ended: enough for a recognition, not for an alert.
    expect(formatCredit(100)).toBe('1.00 CZK')
    expect(formatCredit(50)).toBe('0.50 CZK')
    expect(formatCredit(5)).toBe('0.05 CZK')
  })

  it('formats an empty balance, which is what production shows today', () => {
    // SIGNUP_GRANT_MINOR is 0 in prod until there is abuse protection in front of the form, so
    // this is the first thing a newly registered hunter actually sees.
    expect(formatCredit(0)).toBe('0.00 CZK')
  })

  it('keeps the sign in front where a balance has somehow gone negative', () => {
    // The database forbids it (hunter_credit_not_negative), so this is defensive: if it ever
    // arrives, show it rather than rendering "-1.-50 CZK" or similar nonsense.
    expect(formatCredit(-150)).toBe('-1.50 CZK')
  })
})

describe('alertsRemaining', () => {
  it('turns the signup grant into the ten alerts it was priced as', () => {
    // 2500 at 250 per alert (recognition 100 + SMS 150) is the arithmetic the grant was chosen
    // for -- see V4__create_credit_tables.sql.
    expect(alertsRemaining(2500, 250)).toBe(10)
  })

  it('rounds down, so it never promises an alert that cannot be sent', () => {
    expect(alertsRemaining(2499, 250)).toBe(9)
    expect(alertsRemaining(499, 250)).toBe(1)
    expect(alertsRemaining(249, 250)).toBe(0)
  })

  it('is zero for an empty balance', () => {
    expect(alertsRemaining(0, 250)).toBe(0)
  })

  it('is zero rather than infinite when the price is zero', () => {
    // A server that sent 0 should not make the page claim unlimited alerts. The endpoint's
    // @Min(1) makes this unlikely, which is exactly why it would go unnoticed.
    expect(alertsRemaining(2500, 0)).toBe(0)
  })

  it('is zero rather than NaN for nonsense input', () => {
    expect(alertsRemaining(Number.NaN, 250)).toBe(0)
    expect(alertsRemaining(2500, Number.NaN)).toBe(0)
    expect(alertsRemaining(2500, -250)).toBe(0)
  })
})

describe('alertsLabel', () => {
  it('does not say "1 alerts"', () => {
    expect(alertsLabel(1)).toBe('1 alert')
  })

  it('pluralises everything else, including zero', () => {
    expect(alertsLabel(0)).toBe('0 alerts')
    expect(alertsLabel(10)).toBe('10 alerts')
  })
})

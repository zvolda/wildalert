"""Charging a hunter for one classified photo, behind a swappable interface.

This is recognition's half of the two-part price: it debits `RECOGNITION_DEBIT` (100 haléře by
default) per photo, where notification debits `SMS_DEBIT` (150) per alert. Both go to the same
user-account endpoint, which resolves the hunter and takes the money in one transaction.

**It is the Python mirror of notification's `HunterClient` + `ChargeDtos`** — a deliberate copy,
the same call this project already makes for the event contracts: ~120 lines of infrastructure
against a cross-service module in two languages. If a third service charges, that becomes a
module; until then the duplication is cheaper than the coupling.

Three things are deliberately different from notification's copy, because recognition's job is:

1. **It asks for no notice.** `canDeliverNotice=false` on every request, so user-account does not
   *claim* the "low balance" / "out of credit" warning for this charge. Recognition sends no SMS,
   so a notice handed to it would be swallowed — and user-account only issues each warning once
   per depletion cycle, so swallowing it means the hunter is never told. The warning is left for
   the `SMS_DEBIT` that follows, which rides out on an alert that is going anyway.
2. **The phone number is parsed away.** The charge response carries one (notification needs it);
   `extra="ignore"` drops it here, so it is never held in memory longer than the parse and never
   logged. Recognition has no business knowing it.
3. **A refusal is not an error.** No credit means the photo is not classified and no
   `AnimalRecognized` is published, so nobody is texted and there is nothing to compensate. See
   `app.handler`.
"""

from __future__ import annotations

import logging
from enum import StrEnum
from functools import lru_cache
from typing import Protocol
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from app.config import Settings, get_settings

log = logging.getLogger("recognition.credit")

# What is being paid for. Must be one of user-account's `EntryType` names, and it is half of the
# idempotency key `(entry_type, external_ref)` — which is why one photo can be charged a
# RECOGNITION_DEBIT here and an SMS_DEBIT by notification under the same event id without the
# second looking like a duplicate.
ENTRY_TYPE = "RECOGNITION_DEBIT"


class ChargeOutcome(StrEnum):
    """What happened to a charge attempt. Mirrors user-account's `ChargeOutcome`.

    **Parsed strictly**: an outcome we do not know is a semantic change this service has to be
    taught about, and guessing is the one thing we must not do — guess CHARGED and we run the model
    for free, guess INSUFFICIENT and we drop a photo that was paid for. An unknown value therefore
    fails the push, which Pub/Sub redelivers and eventually dead-letters: loud, and in the right
    place.
    """

    #: The balance was debited by this call. Classify the photo.
    CHARGED = "CHARGED"

    #: An earlier attempt already charged this event. **A success**, and the ordinary answer to a
    #: redelivery — the charge means "this photo is paid for", which stays true however many times
    #: the event arrives. Classify it again (we have no stored result to reuse) and publish; the
    #: hunter is not charged twice.
    ALREADY_CHARGED = "ALREADY_CHARGED"

    #: Not enough credit. Nothing was written, and the photo is not classified.
    INSUFFICIENT = "INSUFFICIENT"

    #: The hunter is switched off. Nothing was written, and the photo is not classified.
    INACTIVE = "INACTIVE"


class ChargeRequest(BaseModel):
    """Body of the charge call, camelCase on the wire like the event contracts."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    #: The ImageReceived `eventId`, which becomes AnimalRecognized's `sourceEventId`. **The id that
    #: survives redelivery** — charging on anything fresher would debit one photo again and again.
    source_event_id: str
    amount_minor: int
    entry_type: str = ENTRY_TYPE
    #: Always false here; see the module docstring. Omitting it would default to true on
    #: user-account's side, which is right for notification and wrong for us.
    can_deliver_notice: bool = False


class Charge(BaseModel):
    """The answer, as recognition uses it. Everything else in the response is dropped."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

    hunter_id: UUID
    outcome: ChargeOutcome
    balance_after_minor: int

    @property
    def payable(self) -> bool:
        """Whether this photo may be classified. Both charged outcomes say yes.

        Expressed here rather than at the call site so that the two-success-values rule lives in
        one place: reading ALREADY_CHARGED as a failure is the easiest mistake to make against this
        contract, and it would drop every redelivered photo.
        """
        return self.outcome in (ChargeOutcome.CHARGED, ChargeOutcome.ALREADY_CHARGED)


class CreditClient(Protocol):
    """Charges a hunter for one photo, or reports that there is nobody to charge.

    Returns None when the photo belongs to no hunter — mail to a made-up address on our domain, or
    a hunter deleted since the photo arrived. Raises on anything else, so the push endpoint answers
    5xx and Pub/Sub redelivers: a user-account that is cold, unreachable or refusing our credential
    must cost a retry, never a silently unclassified photo.
    """

    def charge_by_inbound_address(self, address: str, request: ChargeRequest) -> Charge | None: ...

    def charge_by_id(self, hunter_id: UUID, request: ChargeRequest) -> Charge | None: ...


class TokenSource(Protocol):
    """Supplies the `Authorization` header value for a call to user-account."""

    def header(self) -> str: ...


class GcpIdTokenSource:
    """A Google-signed ID token for Cloud Run's "Require authentication", audience = the base URL.

    The Python mirror of notification's `GcpIdTokenUserAccountAuth`, and the same two traps apply:
    Cloud Run wants an **ID** token, not an access token, and its **audience must match the target
    URL string exactly** — the `-ey` form and the project-number form of the same service are
    different audiences.

    `google-auth` comes in with `google-cloud-pubsub`, so this needs no new dependency, but it is
    imported lazily all the same: the default configuration has no token source at all, and a host
    with no Application Default Credentials must still be able to start the service. The credentials
    object caches the token and refreshes it when it expires, so this is not a metadata-server call
    per photo.
    """

    def __init__(self, audience: str) -> None:
        from google.auth.transport.requests import Request
        from google.oauth2.id_token import fetch_id_token_credentials

        self._request = Request()
        self._credentials = fetch_id_token_credentials(audience, request=self._request)
        log.info("Calls to user-account will carry a Google ID token for audience %s", audience)

    def header(self) -> str:
        if not self._credentials.valid:
            self._credentials.refresh(self._request)
        return f"Bearer {self._credentials.token}"


class UserAccountCreditClient:
    """Calls user-account's charge endpoints over HTTP.

    One call resolves the hunter *and* debits them, rather than a lookup followed by a charge:
    there is then no looked-up-but-not-charged state, only one round trip against a service that
    scales to zero, and the "may this photo be classified?" decision is made inside the transaction
    that holds the money.

    A 404 means the address belongs to no hunter, and nothing was charged. **Every other failure is
    raised on purpose** — a 403 from a missing credential, a read timeout on a cold start,
    user-account down — so Pub/Sub redelivers. Nothing is charged on that path either: on the far
    side the resolve and the debit are one transaction, so a call that does not come back
    successfully has moved no money.
    """

    def __init__(self, client: httpx.Client, token_source: TokenSource | None = None) -> None:
        self._client = client
        self._token_source = token_source

    def charge_by_inbound_address(self, address: str, request: ChargeRequest) -> Charge | None:
        return self._post(
            "/api/hunters/by-inbound-address/charges", request, params={"address": address}
        )

    def charge_by_id(self, hunter_id: UUID, request: ChargeRequest) -> Charge | None:
        return self._post(f"/api/hunters/{hunter_id}/charges", request)

    def _post(
        self, path: str, request: ChargeRequest, params: dict[str, str] | None = None
    ) -> Charge | None:
        # The content type is set by hand because the body is serialised by pydantic (so that the
        # aliases and types are its business, not httpx's) and passed as raw `content`, which
        # httpx sends with no type at all. Spring then answers **415 before the controller runs** —
        # found against the real service, invisible to a mocked transport, hence the test for it.
        headers = {"Content-Type": "application/json"}
        if self._token_source:
            headers["Authorization"] = self._token_source.header()

        response = self._client.post(
            path,
            params=params,
            headers=headers,
            content=request.model_dump_json(by_alias=True),
        )
        if response.status_code == httpx.codes.NOT_FOUND:
            return None
        # Raises for 4xx/5xx alike: a 400 from here is our own bug, and it should be as loud as an
        # outage rather than quietly costing nothing.
        response.raise_for_status()
        return Charge.model_validate_json(response.content)


def build_credit_client(settings: Settings) -> CreditClient:
    """Creates the client that charges user-account.

    There is no fake default here, unlike `Classifier`, `ImageSource` and `EventPublisher`. Those
    stand in for things that cost money or need credentials; user-account is ours, it is up in
    every environment that has a broker, and the whole point of this slice is that an empty balance
    stops the model running. A default that quietly skipped the charge would be the
    forgetting-an-env-var hole that `billing.signup-grant-minor` was changed to avoid.

    Timeouts mirror notification's, and for the same reason: a timeout here raises, the push
    answers 5xx and Pub/Sub redelivers, so waiting out a cold-starting user-account (Spring Boot +
    Flyway + Cloud SQL, measured at over 10s) is cheaper than burning a delivery attempt. 25s stays
    well inside this subscription's 600s ack deadline.
    """
    client = httpx.Client(
        base_url=settings.user_account_base_url,
        timeout=httpx.Timeout(connect=2.0, read=25.0, write=10.0, pool=5.0),
    )
    token_source = (
        GcpIdTokenSource(settings.user_account_base_url)
        if settings.user_account_auth == "gcp-id-token"
        else None
    )
    return UserAccountCreditClient(client, token_source)


@lru_cache
def get_credit_client() -> CreditClient:
    """FastAPI dependency; cached so the HTTP connection pool is reused. Tests override it."""
    return build_credit_client(get_settings())

"""Tests for the charge call to user-account.

They run against `httpx.MockTransport` rather than a stub HTTP server: the request really is built
by httpx and the response really is parsed, so what is exercised is the URL, the query parameter,
the JSON body, the header and the status handling — everything except the socket.

The response bodies here are written from user-account's `ChargeResponse`. The project has been
bitten by a guessed response shape before (GoSMS, slice 12), so they include the fields this
service deliberately ignores — `phone` and `notice` — to prove ignoring them is what happens.
"""

import json
from uuid import UUID

import httpx
import pytest
from pydantic import ValidationError

from app.credit import (
    Charge,
    ChargeOutcome,
    ChargeRequest,
    UserAccountCreditClient,
    build_credit_client,
)
from app.config import Settings

HUNTER = UUID("a87cf72a-3612-4263-bc4f-a789152157ec")
ADDRESS = "7f3k9qabcdef@wildalert.local"
EVENT_ID = "aaaaaaaa-0000-4000-8000-000000000001"


def _response_body(
    outcome: str = "CHARGED", balance: int = 2400, notice: str = "NONE"
) -> bytes:
    return json.dumps(
        {
            "hunterId": str(HUNTER),
            "phone": "+420100000000",
            "active": True,
            "outcome": outcome,
            "balanceAfterMinor": balance,
            "notice": notice,
        }
    ).encode()


def _client(handler, token_source=None) -> tuple[UserAccountCreditClient, list[httpx.Request]]:
    seen: list[httpx.Request] = []

    def record(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return handler(request)

    http = httpx.Client(
        base_url="http://user-account:8081", transport=httpx.MockTransport(record)
    )
    return UserAccountCreditClient(http, token_source), seen


def _request() -> ChargeRequest:
    return ChargeRequest(source_event_id=EVENT_ID, amount_minor=100)


def test_charges_by_inbound_address():
    client, seen = _client(lambda r: httpx.Response(200, content=_response_body()))

    charge = client.charge_by_inbound_address(ADDRESS, _request())

    assert seen[0].method == "POST"
    assert seen[0].url.path == "/api/hunters/by-inbound-address/charges"
    assert seen[0].url.params["address"] == ADDRESS
    # Pinned because its absence is not visible in a mocked transport: the body is serialised by
    # pydantic and sent as raw content, which httpx types as nothing, and Spring then answers 415
    # before the controller runs. That happened on the first live call.
    assert seen[0].headers["content-type"] == "application/json"
    body = json.loads(seen[0].content)
    # camelCase on the wire, which is what Jackson expects on the other side.
    assert body == {
        "sourceEventId": EVENT_ID,
        "amountMinor": 100,
        "entryType": "RECOGNITION_DEBIT",
        "canDeliverNotice": False,
    }
    assert charge.hunter_id == HUNTER
    assert charge.outcome is ChargeOutcome.CHARGED
    assert charge.balance_after_minor == 2400
    assert charge.payable is True


def test_charges_by_hunter_id_on_the_legacy_path():
    client, seen = _client(lambda r: httpx.Response(200, content=_response_body()))

    client.charge_by_id(HUNTER, _request())

    assert seen[0].url.path == f"/api/hunters/{HUNTER}/charges"


def test_the_phone_number_is_not_kept():
    """It is in the response because notification needs it. This service has no use for it."""
    client, _ = _client(lambda r: httpx.Response(200, content=_response_body()))

    charge = client.charge_by_inbound_address(ADDRESS, _request())

    assert not hasattr(charge, "phone")
    assert "+420100000000" not in charge.model_dump_json()


def test_no_authorization_header_by_default():
    client, seen = _client(lambda r: httpx.Response(200, content=_response_body()))

    client.charge_by_inbound_address(ADDRESS, _request())

    assert "authorization" not in seen[0].headers


def test_the_id_token_is_sent_when_one_is_configured():
    class FakeTokenSource:
        def header(self) -> str:
            return "Bearer id-token-for-user-account"

    client, seen = _client(
        lambda r: httpx.Response(200, content=_response_body()), token_source=FakeTokenSource()
    )

    client.charge_by_inbound_address(ADDRESS, _request())

    assert seen[0].headers["authorization"] == "Bearer id-token-for-user-account"


def test_an_unknown_address_is_nobody_to_charge():
    client, _ = _client(lambda r: httpx.Response(404, json={"detail": "no such hunter"}))

    assert client.charge_by_inbound_address(ADDRESS, _request()) is None


@pytest.mark.parametrize("status", [401, 403, 400, 500, 503])
def test_every_other_failure_is_raised_so_pubsub_redelivers(status):
    """A cold user-account, a missing credential or our own bad request must cost a retry.

    Swallowing any of these would mean a photo that is silently never classified — the failure
    mode this project keeps running into, and the reason none of them is caught.
    """
    client, _ = _client(lambda r: httpx.Response(status))

    with pytest.raises(httpx.HTTPStatusError):
        client.charge_by_inbound_address(ADDRESS, _request())


def test_an_outcome_we_do_not_know_is_not_guessed():
    client, _ = _client(lambda r: httpx.Response(200, content=_response_body(outcome="DEFERRED")))

    with pytest.raises(ValidationError):
        client.charge_by_inbound_address(ADDRESS, _request())


def test_a_notice_we_do_not_know_is_ignored_rather_than_fatal():
    """The asymmetry with the outcome: a notice is additive and recognition never reads it."""
    client, _ = _client(
        lambda r: httpx.Response(200, content=_response_body(notice="SOMETHING_NEW"))
    )

    charge = client.charge_by_inbound_address(ADDRESS, _request())

    assert charge.outcome is ChargeOutcome.CHARGED


def test_already_charged_is_payable_and_the_refusals_are_not():
    def charge(outcome: str) -> Charge:
        return Charge(hunter_id=HUNTER, outcome=outcome, balance_after_minor=0)

    assert charge("CHARGED").payable is True
    assert charge("ALREADY_CHARGED").payable is True
    assert charge("INSUFFICIENT").payable is False
    assert charge("INACTIVE").payable is False


def test_the_built_client_talks_to_the_configured_url_with_no_token_by_default():
    client = build_credit_client(Settings(user_account_base_url="http://user-account:8081"))

    assert isinstance(client, UserAccountCreditClient)
    assert client._token_source is None
    assert str(client._client.base_url) == "http://user-account:8081"
    # Generous read timeout: a timeout raises, Pub/Sub redelivers, and waiting out a cold
    # user-account is cheaper than burning a delivery attempt.
    assert client._client.timeout.read == 25.0
    assert client._client.timeout.connect == 2.0

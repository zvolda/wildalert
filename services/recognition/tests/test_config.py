"""Tests for the settings that cost money if they are wrong.

The price is pinned here the way notification's is pinned by `SmsCostDefaultTest`: a change to what
a hunter is charged should be a deliberate act that fails a build, not a stray edit that turns up
on someone's statement.
"""

import pytest
from pydantic import ValidationError

from app.config import Settings, get_settings


def test_a_recognition_costs_100_haler_by_default():
    """1.00 CZK per classified photo, the documented price.

    A default is right here and 0 would be wrong: forgetting the variable must charge the normal
    amount rather than run the model for free. That is the opposite call to the signup grant, which
    defaults to nothing — forgetting *that* one must not give credit away.
    """
    assert Settings().recognition_cost_minor == 100


def test_the_price_is_read_from_the_environment(monkeypatch):
    monkeypatch.setenv("RECOGNITION_COST_MINOR", "250")
    get_settings.cache_clear()
    try:
        assert get_settings().recognition_cost_minor == 250
    finally:
        get_settings.cache_clear()


def test_a_free_recognition_cannot_be_configured():
    """user-account's own @Min(1) would reject it too; this fails before the first photo."""
    with pytest.raises(ValidationError):
        Settings(recognition_cost_minor=0)


def test_user_account_defaults_to_localhost_with_no_authentication():
    settings = Settings()

    assert settings.user_account_base_url == "http://localhost:8081"
    # In the cloud this is gcp-id-token, because user-account requires authentication there.
    assert settings.user_account_auth == "none"

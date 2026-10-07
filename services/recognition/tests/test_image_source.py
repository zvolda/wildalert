import io

import boto3
import pytest
from botocore.exceptions import ClientError
from botocore.response import StreamingBody
from botocore.stub import Stubber

from app.config import Settings
from app.image_source import (
    ImageNotFoundError,
    LocalFolderImageSource,
    R2ImageSource,
    build_image_source,
)

KEY = "inbound/2026/09/04/0b6c1f0e-5d2a-4c1e-9a53-2f7d8e4b9c10.png"
IMAGE = b"\x89PNG\r\n\x1a\n fake image bytes"


# --- Local folder -------------------------------------------------------------------------


def test_local_reads_bytes_stored_under_root_slash_key(tmp_path):
    # Same layout email-ingestion's FileSystemImageStore writes: <root>/<key>.
    file = tmp_path / KEY
    file.parent.mkdir(parents=True)
    file.write_bytes(IMAGE)

    assert LocalFolderImageSource(tmp_path).get(KEY) == IMAGE


def test_local_missing_key_raises_image_not_found(tmp_path):
    with pytest.raises(ImageNotFoundError) as e:
        LocalFolderImageSource(tmp_path).get(KEY)

    assert e.value.key == KEY


def test_local_rejects_keys_that_escape_the_root(tmp_path):
    root = tmp_path / "images"
    root.mkdir()
    (tmp_path / "secret.txt").write_bytes(b"not an image")

    with pytest.raises(ValueError):
        LocalFolderImageSource(root).get("../secret.txt")


# --- R2 (boto3, stubbed — no network or credentials) ----------------------------------------


def _stubbed_r2():
    client = boto3.client(
        "s3",
        endpoint_url="https://account.r2.cloudflarestorage.com",
        aws_access_key_id="test",
        aws_secret_access_key="test",
        region_name="auto",
    )
    return R2ImageSource(client, bucket="wildalert-images"), Stubber(client)


def test_r2_returns_object_body_for_bucket_and_key():
    source, stubber = _stubbed_r2()
    stubber.add_response(
        "get_object",
        {"Body": StreamingBody(io.BytesIO(IMAGE), len(IMAGE))},
        expected_params={"Bucket": "wildalert-images", "Key": KEY},
    )

    with stubber:
        assert source.get(KEY) == IMAGE
    stubber.assert_no_pending_responses()


def test_r2_missing_object_raises_image_not_found():
    source, stubber = _stubbed_r2()
    stubber.add_client_error("get_object", service_error_code="NoSuchKey", http_status_code=404)

    with stubber, pytest.raises(ImageNotFoundError):
        source.get(KEY)


def test_r2_other_errors_are_not_treated_as_missing():
    source, stubber = _stubbed_r2()
    stubber.add_client_error("get_object", service_error_code="AccessDenied", http_status_code=403)

    with stubber, pytest.raises(ClientError):
        source.get(KEY)


# --- Selection by config --------------------------------------------------------------------


def test_default_settings_build_a_local_source():
    assert isinstance(build_image_source(Settings()), LocalFolderImageSource)


def test_r2_settings_build_an_r2_source():
    settings = Settings(
        image_source="r2",
        r2_endpoint="https://account.r2.cloudflarestorage.com",
        r2_bucket="wildalert-images",
        r2_access_key="key",
        r2_secret_key="secret",
    )

    assert isinstance(build_image_source(settings), R2ImageSource)


def test_r2_without_credentials_fails_fast_naming_what_is_missing():
    with pytest.raises(ValueError, match="R2_BUCKET"):
        build_image_source(Settings(image_source="r2", r2_endpoint="https://x"))


def test_unknown_image_source_is_rejected():
    with pytest.raises(ValueError, match="Unknown image source"):
        build_image_source(Settings(image_source="gcs"))

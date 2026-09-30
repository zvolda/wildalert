"""Fetching stored images behind a swappable interface.

An ImageReceived event only carries a storage key, so recognition has to turn that key back into
image bytes. Callers talk to the `ImageSource` protocol; the backend is chosen by config: a local
folder by default (shared with email-ingestion's FileSystemImageStore, so the pipeline runs with
no cloud bucket) or Cloudflare R2 via boto3 — the Python mirror of the Kotlin `ImageStore`.
Both backends use the same key layout: `inbound/yyyy/MM/dd/<uuid>.<ext>`.
"""

from pathlib import Path
from typing import Protocol

import boto3
from botocore.exceptions import ClientError

from app.config import Settings


class ImageNotFoundError(Exception):
    """No image is stored under the requested key."""

    def __init__(self, key: str) -> None:
        super().__init__(f"No image stored under key '{key}'")
        self.key = key


class ImageSource(Protocol):
    """Returns the raw bytes of the image stored under a key."""

    def get(self, key: str) -> bytes: ...


class LocalFolderImageSource:
    """Reads `<root>/<key>` from a local folder — the counterpart of FileSystemImageStore."""

    def __init__(self, root: str | Path) -> None:
        self._root = Path(root).resolve()

    def get(self, key: str) -> bytes:
        path = (self._root / key).resolve()
        # Keys arrive in events, so never let one point outside the image folder ("../..").
        if not path.is_relative_to(self._root):
            raise ValueError(f"Storage key escapes the image root: '{key}'")
        try:
            return path.read_bytes()
        except (FileNotFoundError, IsADirectoryError) as e:
            raise ImageNotFoundError(key) from e


class R2ImageSource:
    """Reads objects from Cloudflare R2 through its S3-compatible API (boto3)."""

    def __init__(self, client, bucket: str) -> None:
        self._client = client
        self._bucket = bucket

    def get(self, key: str) -> bytes:
        try:
            response = self._client.get_object(Bucket=self._bucket, Key=key)
        except ClientError as e:
            if e.response.get("Error", {}).get("Code") in ("NoSuchKey", "404"):
                raise ImageNotFoundError(key) from e
            raise  # auth/network/bucket problems are real failures, not a missing image
        return response["Body"].read()


def build_image_source(settings: Settings) -> ImageSource:
    """Creates the configured image source: a local folder (default) or R2."""
    if settings.image_source == "local":
        return LocalFolderImageSource(settings.image_root)
    if settings.image_source == "r2":
        missing = [
            name
            for name, value in {
                "R2_ENDPOINT": settings.r2_endpoint,
                "R2_BUCKET": settings.r2_bucket,
                "R2_ACCESS_KEY": settings.r2_access_key,
                "R2_SECRET_KEY": settings.r2_secret_key,
            }.items()
            if not value
        ]
        if missing:
            raise ValueError(f"R2 image source needs: {', '.join(missing)}")
        client = boto3.client(
            "s3",
            endpoint_url=settings.r2_endpoint,
            aws_access_key_id=settings.r2_access_key,
            aws_secret_access_key=settings.r2_secret_key,
            # R2 ignores the region but boto3 needs one; "auto" is Cloudflare's recommendation.
            region_name=settings.r2_region,
        )
        return R2ImageSource(client, settings.r2_bucket)
    raise ValueError(f"Unknown image source '{settings.image_source}' (expected 'local' or 'r2')")

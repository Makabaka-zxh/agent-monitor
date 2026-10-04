"""Validate and normalize user-supplied raster avatars without external URLs."""
from __future__ import annotations

import base64
import binascii
from io import BytesIO
import re
import warnings

from PIL import Image, ImageOps, UnidentifiedImageError

AVATAR_BYTES = 256 * 1024
AVATAR_DATA_URL_LENGTH = 4 * ((AVATAR_BYTES + 2) // 3) + 32


def normalize_avatar(value: str) -> str:
    if value == "":
        return ""
    if len(value) > AVATAR_DATA_URL_LENGTH:
        raise ValueError("头像文件不能超过 256 KB")
    match = re.fullmatch(r"data:image/(jpeg|png|webp);base64,([A-Za-z0-9+/]*={0,2})", value)
    if not match:
        raise ValueError("头像仅支持 JPEG、PNG 或 WebP 图片")
    try:
        raw = base64.b64decode(match[2], validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("头像图片格式无效") from None
    if not raw or len(raw) > AVATAR_BYTES:
        raise ValueError("头像文件不能超过 256 KB")
    expected = {"jpeg": "JPEG", "png": "PNG", "webp": "WEBP"}[match[1]]
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(raw), formats=[expected]) as picture:
                if picture.format != expected or getattr(picture, "n_frames", 1) != 1:
                    raise ValueError("头像必须是单张静态图片")
                if not (1 <= picture.width <= 1024 and 1 <= picture.height <= 1024):
                    raise ValueError("头像尺寸不能超过 1024 × 1024")
                picture.verify()
            with Image.open(BytesIO(raw), formats=[expected]) as picture:
                picture.load()
                oriented = ImageOps.exif_transpose(picture)
                oriented.thumbnail((512, 512), Image.Resampling.LANCZOS)
                # Copy pixels into a fresh image; never retain EXIF, comments,
                # profiles, trailing payloads, or another frame from the upload.
                pixels = oriented.convert("RGBA")
                clean = Image.new("RGB", pixels.size, "white")
                clean.paste(pixels, mask=pixels.getchannel("A"))
                output = BytesIO()
                clean.save(output, format="JPEG", quality=85, optimize=True)
        normalized = output.getvalue()
        if len(normalized) > AVATAR_BYTES:
            raise ValueError("头像图片过大，请降低分辨率后重试")
    except (UnidentifiedImageError, OSError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise ValueError("头像图片损坏或格式无效") from None
    return "data:image/jpeg;base64," + base64.b64encode(normalized).decode("ascii")

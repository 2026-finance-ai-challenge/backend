#!/usr/bin/env python3
"""CI 비밀 갱신 시 서버의 복구·수집 설정을 보존한다."""
import os
import re
import tempfile
from pathlib import Path

ROOT = Path("/opt/kmarket")


def parse(text):
    result = {}
    for line in text.splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError("환경 파일 형식이 올바르지 않습니다.")
        result[key] = value
    return result


def merged(current, incoming):
    return {**current, **{key: value for key, value in incoming.items() if value or key not in current}}


def main():
    target = ROOT / "runtime.env"
    incoming = ROOT / "runtime.incoming.env"
    values = merged(parse(target.read_text()) if target.exists() else {}, parse(incoming.read_text()))
    fd, temporary = tempfile.mkstemp(prefix=".runtime.env.", dir=ROOT)
    try:
        with os.fdopen(fd, "w") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write("".join(f"{key}={value}\n" for key, value in values.items()))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
        incoming.unlink()
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


if __name__ == "__main__":
    main()

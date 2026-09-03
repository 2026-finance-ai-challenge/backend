#!/usr/bin/env python3
"""실행·롤백·복구 이미지를 보존하는 KART 전용 이미지 정리."""
import argparse
import fcntl
import json
import subprocess
from pathlib import Path

REPOSITORIES = {"ghcr.io/2026-finance-ai-challenge/backend", "ghcr.io/2026-finance-ai-challenge/ai", "kart-backend", "kart-ai"}


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def inspect(kind, ids):
    return [item for start in range(0, len(ids), 50)
            for item in json.loads(docker(kind, "inspect", *ids[start:start + 50]))]


def plan(images, containers, pins, keep):
    protected = {container["Image"] for container in containers}
    by_reference = {tag: image["Id"] for image in images for tag in (image.get("RepoTags") or [])}
    by_reference.update({image["Id"]: image["Id"] for image in images})
    for pin in pins:
        if pin not in by_reference:
            raise ValueError(f"보호 이미지 확인 실패: {pin}")
        protected.add(by_reference[pin])
    for repository in REPOSITORIES:
        owned = [image for image in images if any(tag.rsplit(":", 1)[0] == repository for tag in image.get("RepoTags") or [])]
        protected.update(image["Id"] for image in sorted(owned, key=lambda image: image["Created"], reverse=True)[:keep])
    candidates = []
    for image in images:
        tags = image.get("RepoTags") or []
        if image["Id"] in protected or not tags:
            continue
        if all(tag.rsplit(":", 1)[0] in REPOSITORIES for tag in tags):
            candidates.append({"id": image["Id"], "tags": tags})
    return candidates


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--keep", type=int, default=5)
    parser.add_argument("--already-locked", action="store_true")
    parser.add_argument("--protect-file", default="/opt/kmarket/image-retention-pins.txt")
    args = parser.parse_args()
    if args.keep < 3:
        parser.error("최소 3개 버전을 보존해야 합니다.")
    with Path("/opt/kmarket/.deploy.lock").open("a") as lock:
        if not args.already_locked:
            fcntl.flock(lock, fcntl.LOCK_EX)
        ids = list(dict.fromkeys(docker("image", "ls", "-q", "--no-trunc").splitlines()))
        images = inspect("image", ids)
        container_ids = docker("ps", "-aq").splitlines()
        containers = inspect("container", container_ids)
        pin_path = Path(args.protect_file)
        pins = [line.strip() for line in pin_path.read_text().splitlines() if line.strip() and not line.startswith("#")] if pin_path.exists() else []
        for name in ("backend-last-good.image", "ai-last-good.image"):
            last_good = Path("/opt/kmarket") / name
            if last_good.exists():
                pins.append(last_good.read_text().strip())
        candidates = plan(images, containers, pins, args.keep)
        print(json.dumps({"apply": args.apply, "retained": len(images) - len(candidates), "candidates": candidates}, ensure_ascii=False))
        if args.apply:
            for item in candidates:
                # 강제 삭제 없이 Docker의 컨테이너 참조 보호도 적용한다.
                for tag in item["tags"]:
                    subprocess.run(["docker", "image", "rm", tag], check=True, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()

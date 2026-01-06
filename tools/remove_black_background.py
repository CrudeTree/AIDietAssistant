import argparse
import os
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Tuple

from PIL import Image


@dataclass(frozen=True)
class Options:
    threshold: int
    dry_run: bool
    backup_dir: Path | None
    output_dir: Path | None
    in_place: bool


def is_near_black(r: int, g: int, b: int, a: int, threshold: int) -> bool:
    if a == 0:
        return False
    return r <= threshold and g <= threshold and b <= threshold


def iter_target_files(res_root: Path, globs: Iterable[str]) -> Iterable[Path]:
    for g in globs:
        yield from res_root.glob(g)


def ensure_parent(p: Path) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)


def backup_file(src: Path, backup_dir: Path) -> None:
    rel = src.as_posix().lstrip("/")
    dst = backup_dir / rel
    ensure_parent(dst)
    if not dst.exists():
        dst.write_bytes(src.read_bytes())


def save_image(img: Image.Image, dst: Path) -> None:
    ensure_parent(dst)
    ext = dst.suffix.lower()
    if ext == ".webp":
        img.save(dst, lossless=True, quality=100, method=6)
    elif ext == ".png":
        img.save(dst, optimize=True)
    else:
        # fallback
        img.save(dst)


def remove_black_background_flood(img: Image.Image, threshold: int) -> Tuple[Image.Image, int]:
    """
    Removes near-black background by flood-filling from the image border.
    This avoids deleting black details inside the icon.
    Returns (new_image, num_pixels_made_transparent).
    """
    rgba = img.convert("RGBA")
    px = rgba.load()
    w, h = rgba.size
    if w == 0 or h == 0:
        return rgba, 0

    visited = bytearray(w * h)
    q: deque[Tuple[int, int]] = deque()

    def push_if_bg(x: int, y: int) -> None:
        idx = y * w + x
        if visited[idx]:
            return
        r, g, b, a = px[x, y]
        if is_near_black(r, g, b, a, threshold):
            visited[idx] = 1
            q.append((x, y))

    # Seed with border pixels
    for x in range(w):
        push_if_bg(x, 0)
        push_if_bg(x, h - 1)
    for y in range(h):
        push_if_bg(0, y)
        push_if_bg(w - 1, y)

    removed = 0
    while q:
        x, y = q.popleft()
        r, g, b, a = px[x, y]
        if a != 0:
            px[x, y] = (r, g, b, 0)
            removed += 1

        # 4-neighborhood
        if x > 0:
            push_if_bg(x - 1, y)
        if x + 1 < w:
            push_if_bg(x + 1, y)
        if y > 0:
            push_if_bg(x, y - 1)
        if y + 1 < h:
            push_if_bg(x, y + 1)

    return rgba, removed


def process_file(path: Path, opts: Options) -> Tuple[bool, int]:
    with Image.open(path) as im:
        out, removed = remove_black_background_flood(im, threshold=opts.threshold)
        changed = removed > 0

        if not changed:
            return False, 0

        if opts.dry_run:
            return True, removed

        if opts.backup_dir is not None:
            backup_file(path, opts.backup_dir)

        if opts.output_dir is not None:
            dst = opts.output_dir / path.relative_to(path.anchor)
        elif opts.in_place:
            dst = path
        else:
            raise ValueError("Either --in-place or --output-dir must be set.")

        save_image(out, dst)
        return True, removed


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Batch-remove near-black backgrounds (connected to border) from Android res images."
    )
    ap.add_argument(
        "--res-root",
        default="app/src/main/res",
        help="Path to Android res folder (default: app/src/main/res)",
    )
    ap.add_argument(
        "--glob",
        action="append",
        default=[
            "drawable-nodpi/ic_food_*.webp",
        ],
        help="Glob(s) under res-root to process (repeatable). Default: drawable-nodpi/ic_food_*.webp",
    )
    ap.add_argument(
        "--threshold",
        type=int,
        default=18,
        help="Near-black threshold (0..255). Higher removes more dark pixels (default: 18).",
    )
    ap.add_argument(
        "--dry-run",
        action="store_true",
        help="Scan and report changes without writing files.",
    )
    ap.add_argument(
        "--backup-dir",
        default="tools/_icon_backups",
        help="Backup directory (relative or absolute). Set to empty to disable. Default: tools/_icon_backups",
    )
    ap.add_argument(
        "--output-dir",
        default="",
        help="Write outputs here instead of in-place. If empty, writes in-place.",
    )
    args = ap.parse_args()

    res_root = Path(args.res_root)
    backup_dir = Path(args.backup_dir) if args.backup_dir else None
    output_dir = Path(args.output_dir) if args.output_dir else None

    opts = Options(
        threshold=max(0, min(255, args.threshold)),
        dry_run=bool(args.dry_run),
        backup_dir=backup_dir,
        output_dir=output_dir,
        in_place=(output_dir is None),
    )

    files = sorted(set(iter_target_files(res_root, args.glob)))
    if not files:
        print(f"No files matched under {res_root} for globs: {args.glob}")
        return

    changed_count = 0
    total_removed = 0
    for p in files:
        if not p.is_file():
            continue
        try:
            changed, removed = process_file(p, opts)
            if changed:
                changed_count += 1
                total_removed += removed
                print(f"{p}: removed {removed} background pixels")
        except Exception as e:
            print(f"{p}: ERROR: {e}")

    mode = "DRY RUN" if opts.dry_run else "DONE"
    print(f"{mode}: changed {changed_count}/{len(files)} files, total pixels cleared: {total_removed}")


if __name__ == "__main__":
    main()



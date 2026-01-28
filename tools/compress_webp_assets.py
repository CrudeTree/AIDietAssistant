import argparse
import datetime as dt
import os
import zipfile
from pathlib import Path

from PIL import Image


def bytes_human(n: int) -> str:
    for unit in ["B", "KB", "MB", "GB"]:
        if n < 1024 or unit == "GB":
            return f"{n:.2f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024
    return f"{n:.2f} GB"


def make_backup_zip(src_dir: Path, backup_dir: Path) -> Path:
    backup_dir.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    out = backup_dir / f"{src_dir.name}_backup_{stamp}.zip"
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in src_dir.rglob("*"):
            if p.is_file():
                z.write(p, p.relative_to(src_dir))
    return out


def should_process(p: Path) -> bool:
    name = p.name.lower()
    if not name.endswith(".webp"):
        return False
    return name.startswith("ic_food_") or name.startswith("ic_phase_")

def should_process_any_webp(p: Path) -> bool:
    return p.name.lower().endswith(".webp")


def max_size_for(p: Path, max_food: int, max_phase: int, max_other: int) -> int:
    name = p.name.lower()
    if name.startswith("ic_food_"):
        return max_food
    if name.startswith("ic_phase_"):
        # Phase banners can be larger, but still don't need 1000+ for phones.
        return max_phase
    return max_other


def compress_one_webp(
    path: Path,
    quality: int,
    alpha_quality: int,
    max_food: int,
    max_phase: int,
    max_other: int
) -> tuple[int, int, bool]:
    """
    Returns (bytes_before, bytes_after, changed)
    """
    before = path.stat().st_size
    try:
        img = Image.open(path)
        img.load()
    except Exception:
        return before, before, False

    # Resize down only if larger than our max target.
    max_side = max_size_for(path, max_food=max_food, max_phase=max_phase, max_other=max_other)
    w, h = img.size
    scale = min(1.0, max_side / max(w, h))
    if scale < 1.0:
        new_w = max(1, int(round(w * scale)))
        new_h = max(1, int(round(h * scale)))
        img = img.resize((new_w, new_h), Image.Resampling.LANCZOS)

    # Always write a lossy WebP with alpha preserved.
    tmp = path.with_suffix(path.suffix + ".tmp")
    save_kwargs = dict(
        format="WEBP",
        quality=quality,
        method=6,
        alpha_quality=alpha_quality,
        optimize=True,
    )

    # Ensure alpha survives (RGBA) if it exists.
    if img.mode not in ("RGB", "RGBA"):
        img = img.convert("RGBA")

    img.save(tmp, **save_kwargs)
    after = tmp.stat().st_size

    # Only replace if smaller (or if we resized).
    if after < before or scale < 1.0:
        tmp.replace(path)
        return before, after, True
    else:
        tmp.unlink(missing_ok=True)
        return before, before, False


def compress_one_png(path: Path) -> tuple[int, int, bool]:
    """
    Lossless PNG optimize only.
    Returns (bytes_before, bytes_after, changed)
    """
    before = path.stat().st_size
    try:
        img = Image.open(path)
        img.load()
    except Exception:
        return before, before, False

    tmp = path.with_suffix(path.suffix + ".tmp")
    try:
        # Preserve alpha if any.
        if img.mode not in ("RGB", "RGBA"):
            img = img.convert("RGBA")
        img.save(tmp, format="PNG", optimize=True)
        after = tmp.stat().st_size
        if after < before:
            tmp.replace(path)
            return before, after, True
        tmp.unlink(missing_ok=True)
        return before, before, False
    except Exception:
        tmp.unlink(missing_ok=True)
        return before, before, False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project-root", required=True)
    ap.add_argument("--backup-dir", default="image_backups")
    ap.add_argument("--quality", type=int, default=70)
    ap.add_argument("--alpha-quality", type=int, default=80)
    ap.add_argument("--all-webp", action="store_true", help="Process all .webp files (not just ic_food_/ic_phase_)")
    ap.add_argument("--include-png", action="store_true", help="Also optimize .png files (lossless)")
    ap.add_argument("--max-food", type=int, default=512)
    ap.add_argument("--max-phase", type=int, default=1024)
    ap.add_argument("--max-other", type=int, default=1536)
    ap.add_argument("--dir", action="append", dest="dirs", required=True)
    args = ap.parse_args()

    root = Path(args.project_root).resolve()
    backup_dir = (root / args.backup_dir).resolve()

    total_before = 0
    total_after = 0
    changed_count = 0
    scanned_count = 0

    for rel in args.dirs:
        src = (root / rel).resolve()
        if not src.exists():
            print(f"SKIP missing dir: {src}")
            continue

        # Backup the directory once.
        backup_zip = make_backup_zip(src, backup_dir)
        print(f"Backup created: {backup_zip}")

        # WEBP
        for p in src.rglob("*.webp"):
            if not (should_process_any_webp(p) if args.all_webp else should_process(p)):
                continue
            scanned_count += 1
            b, a, changed = compress_one_webp(
                p,
                quality=args.quality,
                alpha_quality=args.alpha_quality,
                max_food=args.max_food,
                max_phase=args.max_phase,
                max_other=args.max_other
            )
            total_before += b
            total_after += a
            if changed:
                changed_count += 1

        # PNG (lossless optimize only)
        if args.include_png:
            for p in src.rglob("*.png"):
                scanned_count += 1
                b, a, changed = compress_one_png(p)
                total_before += b
                total_after += a
                if changed:
                    changed_count += 1

    saved = total_before - total_after
    pct = (saved / total_before * 100.0) if total_before else 0.0
    print("")
    scope = "all-webp" if args.all_webp else "ic_food_*/ic_phase_* only"
    print(f"Scanned: {scanned_count} files ({scope}{' + png' if args.include_png else ''})")
    print(f"Changed: {changed_count}")
    print(f"Before:  {bytes_human(total_before)}")
    print(f"After:   {bytes_human(total_after)}")
    print(f"Saved:   {bytes_human(saved)} ({pct:.1f}%)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


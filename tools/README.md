## Asset helper scripts

### Remove black backgrounds from food icons (bulk)

This script removes **near-black pixels connected to the image border** (background) and makes them transparent.
It avoids deleting black details inside the icon.

#### Install (Windows PowerShell)

**Important:** Pillow currently supports Python **3.11–3.13** on Windows.  
If you have Python **3.14**, `pip install Pillow` will likely fail because there are no prebuilt Windows wheels yet.

Install Python 3.12 (recommended) or 3.13, then use the `py` launcher to target it:

```powershell
py -3.12 -m pip install -r tools/requirements.txt
```

#### Dry run (recommended first)

```powershell
py -3.12 tools/remove_black_background.py --dry-run
```

#### Apply in-place (with backups)

```powershell
py -3.12 tools/remove_black_background.py
```

Backups are written to `tools/_icon_backups/` the first time each file is processed.

#### If your backgrounds aren’t pure black

Increase the threshold slightly (removes more dark pixels):

```powershell
py -3.12 tools/remove_black_background.py --threshold 28
```

#### Target different folders/files

```powershell
py -3.12 tools/remove_black_background.py --glob "drawable/*.webp" --glob "drawable-nodpi/ic_food_*.webp"
```

---

### Alternative: ImageMagick (no Python)

If you don’t want to install another Python version, you can do the same “remove near-black background connected to the border” using ImageMagick.

#### Install (Windows)

- With winget:

```powershell
winget install ImageMagick.ImageMagick
```

Make sure `magick` works:

```powershell
magick -version
```

#### Test on one file first

This adds a 1px black border, flood-fills from the corner to clear the background (with fuzz), then removes the border.

```powershell
magick "app/src/main/res/drawable-nodpi/ic_food_peas.webp" ^
  -alpha set -bordercolor black -border 1 ^
  -fuzz 8%% -fill none -draw "alpha 0,0 floodfill" ^
  -shave 1x1 ^
  -define webp:lossless=true "C:/temp/ic_food_peas_transparent.webp"
```

If it removes too much, lower `-fuzz` (e.g. `4%`). If it doesn’t remove enough, increase it (e.g. `12%`).

#### Batch process all food icons (creates backups)

```powershell
$src = "app/src/main/res/drawable-nodpi"
$bak = "tools/_icon_backups_imagemagick"
New-Item -ItemType Directory -Force $bak | Out-Null

Get-ChildItem $src -Filter "ic_food_*.webp" | ForEach-Object {
  Copy-Item $_.FullName (Join-Path $bak $_.Name) -Force
  magick $_.FullName `
    -alpha set -bordercolor black -border 1 `
    -fuzz 8% -fill none -draw "alpha 0,0 floodfill" `
    -shave 1x1 `
    -define webp:lossless=true $_.FullName
}
```



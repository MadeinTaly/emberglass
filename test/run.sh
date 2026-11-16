#!/usr/bin/env bash
# Plain-JDK check of the professional math (Pro.java).
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d)
javac -d "$out" app/src/app/emberglass/Pro.java app/src/app/emberglass/Tiff.java test/ProCheck.java
java -cp "$out" app.emberglass.ProCheck "$out/check.tif"
# independent reader: Pillow must see a 6x4 float image with the same values and the JSON description
if python -c "import PIL" 2>/dev/null; then
  python - "$out/check.tif" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1])
assert im.mode == "F" and im.size == (6, 4), (im.mode, im.size)
assert abs(im.getpixel((0, 0)) - 20.5) < 1e-6 and abs(im.getpixel((5, 3)) - (20.5 + 23 * 0.25)) < 1e-6
assert im.tag_v2[270] == '{"unit":"C"}', im.tag_v2[270]
print("TIFF read back ok")
PY
fi

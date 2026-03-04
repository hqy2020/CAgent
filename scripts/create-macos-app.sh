#!/usr/bin/env bash
# create-macos-app.sh — 生成 Ragent.app 桌面启动器
# 用法: ./scripts/create-macos-app.sh [--dest ~/Desktop]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${1:-$HOME/Desktop}"
APP_NAME="Ragent"
APP_PATH="${DEST_DIR}/${APP_NAME}.app"
WORK=$(mktemp -d)

trap 'rm -rf "$WORK"' EXIT

# ── Colors ────────────────────────────────────────
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

printf "${CYAN}[BUILD]${NC} Creating ${APP_NAME}.app...\n"

# ════════════════════════════════════════════════════
# Step 1: Generate icon with Swift + CoreGraphics
# ════════════════════════════════════════════════════
printf "${CYAN}[BUILD]${NC} Generating app icon...\n"

cat > "$WORK/gen_icon.swift" << 'SWIFT_EOF'
import Cocoa

let size: CGFloat = 1024
let outputPath = CommandLine.arguments[1]

let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil,
    pixelsWide: Int(size), pixelsHigh: Int(size),
    bitsPerSample: 8, samplesPerPixel: 4,
    hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB,
    bytesPerRow: 0, bitsPerPixel: 0
)!

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)!
let ctx = NSGraphicsContext.current!.cgContext

let rect = CGRect(x: 0, y: 0, width: size, height: size)
let radius = size * 0.22
let bgPath = CGMutablePath()
bgPath.addRoundedRect(in: rect, cornerWidth: radius, cornerHeight: radius)
let cs = CGColorSpaceCreateDeviceRGB()

// ── 1. Gradient background (dark navy → teal) ──
ctx.saveGState()
ctx.addPath(bgPath)
ctx.clip()

let grad = CGGradient(colorSpace: cs, colorComponents: [
    0.055, 0.055, 0.145, 1.0,   // #0e0e25
    0.040, 0.520, 0.500, 1.0,   // #0a8580
], locations: [0.0, 1.0], count: 2)!
ctx.drawLinearGradient(grad,
    start: CGPoint(x: 0, y: 0),
    end:   CGPoint(x: size, y: size),
    options: [])

// ── 2. Subtle center glow ──
let glow = CGGradient(colorSpace: cs, colorComponents: [
    1, 1, 1, 0.07,
    1, 1, 1, 0.0,
], locations: [0.0, 1.0], count: 2)!
ctx.drawRadialGradient(glow,
    startCenter: CGPoint(x: size * 0.38, y: size * 0.62),
    startRadius: 0,
    endCenter:   CGPoint(x: size * 0.38, y: size * 0.62),
    endRadius:   size * 0.52,
    options: [])

// ── 3. Decorative dots (nodes / AI vibe) ──
let dots: [(CGFloat, CGFloat, CGFloat, CGFloat)] = [
    (0.82, 0.80, 20, 0.18),
    (0.90, 0.88, 13, 0.13),
    (0.76, 0.90, 9,  0.10),
    (0.20, 0.18, 16, 0.14),
    (0.13, 0.26, 10, 0.09),
]
for (rx, ry, r, a) in dots {
    ctx.setFillColor(red: 1, green: 1, blue: 1, alpha: a)
    ctx.fillEllipse(in: CGRect(
        x: size * rx - r, y: size * ry - r,
        width: r * 2, height: r * 2))
}

// ── 4. Thin connecting lines between dots ──
ctx.setStrokeColor(red: 1, green: 1, blue: 1, alpha: 0.06)
ctx.setLineWidth(2)
ctx.move(to: CGPoint(x: size * 0.82, y: size * 0.80))
ctx.addLine(to: CGPoint(x: size * 0.90, y: size * 0.88))
ctx.move(to: CGPoint(x: size * 0.82, y: size * 0.80))
ctx.addLine(to: CGPoint(x: size * 0.76, y: size * 0.90))
ctx.move(to: CGPoint(x: size * 0.20, y: size * 0.18))
ctx.addLine(to: CGPoint(x: size * 0.13, y: size * 0.26))
ctx.strokePath()

ctx.restoreGState()

// ── 5. "R" letter with drop shadow ──
ctx.saveGState()
ctx.addPath(bgPath)
ctx.clip()

let shadowColor = NSColor(white: 0, alpha: 0.35).cgColor
ctx.setShadow(offset: CGSize(width: 0, height: -6), blur: 16, color: shadowColor)

let font = NSFont.systemFont(ofSize: size * 0.56, weight: .heavy)
let text = NSAttributedString(string: "R", attributes: [
    .font: font,
    .foregroundColor: NSColor.white,
])
let ts = text.size()
text.draw(at: NSPoint(
    x: (size - ts.width) / 2,
    y: (size - ts.height) / 2 - size * 0.01
))

ctx.restoreGState()

// ── 6. Bottom accent bar ──
ctx.saveGState()
ctx.addPath(bgPath)
ctx.clip()

let barY = size * 0.12
let barH: CGFloat = 4
let barW = size * 0.25
let barX = (size - barW) / 2
ctx.setFillColor(red: 1, green: 1, blue: 1, alpha: 0.35)
let barPath = CGMutablePath()
barPath.addRoundedRect(in: CGRect(x: barX, y: barY, width: barW, height: barH),
    cornerWidth: barH / 2, cornerHeight: barH / 2)
ctx.addPath(barPath)
ctx.fillPath()

ctx.restoreGState()

NSGraphicsContext.restoreGraphicsState()

// ── Save PNG ──
guard let data = rep.representation(using: .png, properties: [:]) else {
    fputs("Failed to generate PNG\n", stderr)
    exit(1)
}
do {
    try data.write(to: URL(fileURLWithPath: outputPath))
} catch {
    fputs("Failed to write PNG: \(error)\n", stderr)
    exit(1)
}
SWIFT_EOF

# Compile Swift icon generator
if ! swiftc -O "$WORK/gen_icon.swift" -o "$WORK/gen_icon" 2>"$WORK/swift_err.log"; then
  printf "${RED}[ERROR]${NC} Swift compilation failed:\n"
  cat "$WORK/swift_err.log"
  printf "${YELLOW}[WARN]${NC} Creating app without custom icon (you can set one manually)\n"
  ICON_GENERATED=false
else
  "$WORK/gen_icon" "$WORK/icon_1024.png"
  ICON_GENERATED=true
fi

# Create .iconset and .icns
if [ "$ICON_GENERATED" = true ]; then
  printf "${CYAN}[BUILD]${NC} Converting to .icns...\n"
  mkdir -p "$WORK/ragent.iconset"
  for s in 16 32 128 256 512; do
    sips -z "$s" "$s" "$WORK/icon_1024.png" \
      --out "$WORK/ragent.iconset/icon_${s}x${s}.png" >/dev/null 2>&1
    s2=$((s * 2))
    sips -z "$s2" "$s2" "$WORK/icon_1024.png" \
      --out "$WORK/ragent.iconset/icon_${s}x${s}@2x.png" >/dev/null 2>&1
  done
  iconutil -c icns "$WORK/ragent.iconset" -o "$WORK/ragent.icns"
fi

# ════════════════════════════════════════════════════
# Step 2: Create .app bundle
# ════════════════════════════════════════════════════
printf "${CYAN}[BUILD]${NC} Assembling ${APP_NAME}.app bundle...\n"

rm -rf "$APP_PATH"
mkdir -p "$APP_PATH/Contents/MacOS"
mkdir -p "$APP_PATH/Contents/Resources"

# ── Info.plist ──
cat > "$APP_PATH/Contents/Info.plist" << 'PLIST_EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>Ragent</string>
    <key>CFBundleDisplayName</key>
    <string>Ragent</string>
    <key>CFBundleIdentifier</key>
    <string>com.nageoffer.ragent.launcher</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>CFBundleExecutable</key>
    <string>launcher</string>
    <key>CFBundleIconFile</key>
    <string>ragent</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>LSMinimumSystemVersion</key>
    <string>10.15</string>
    <key>LSUIElement</key>
    <true/>
</dict>
</plist>
PLIST_EOF

# ── Launcher script ──
# Uses osascript to open Terminal and run `make start` in the project directory.
# LSUIElement=true means the launcher itself won't appear in the Dock.
cat > "$APP_PATH/Contents/MacOS/launcher" << LAUNCHER_EOF
#!/bin/bash
osascript -e 'tell application "Terminal"
    activate
    do script "cd \"${ROOT_DIR}\" && make start"
end tell'
LAUNCHER_EOF
chmod +x "$APP_PATH/Contents/MacOS/launcher"

# ── Icon ──
if [ "$ICON_GENERATED" = true ]; then
  cp "$WORK/ragent.icns" "$APP_PATH/Contents/Resources/ragent.icns"
fi

# ════════════════════════════════════════════════════
# Done
# ════════════════════════════════════════════════════
printf "\n${GREEN}════════════════════════════════════════════${NC}\n"
printf "${GREEN}  ${APP_NAME}.app 创建成功!${NC}\n"
printf "${GREEN}════════════════════════════════════════════${NC}\n"
printf "  位置: ${CYAN}${APP_PATH}${NC}\n"
printf "  用法: 双击桌面上的 Ragent 图标即可一键启动\n"
printf "\n"
printf "  ${YELLOW}注意:${NC} 此 App 绑定项目路径:\n"
printf "  ${CYAN}${ROOT_DIR}${NC}\n"
printf "  如果项目移动了位置，请重新运行此脚本。\n"
printf "\n"

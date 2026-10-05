$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$proj = 'C:\Users\user\AndroidStudioProjects\YouTubeApp'
$logoDir = Join-Path $proj 'art\logo'
$coverDir = Join-Path $proj 'art\cover'
New-Item -ItemType Directory -Force -Path $logoDir | Out-Null
New-Item -ItemType Directory -Force -Path $coverDir | Out-Null

# --- palette (modified YouTube look: red gradient badge + amber fold) ---
$C1  = [System.Drawing.Color]::FromArgb(255, 255, 68, 54)    # badge gradient start
$C2  = [System.Drawing.Color]::FromArgb(255, 193, 0, 27)     # badge gradient end
$O1  = [System.Drawing.Color]::FromArgb(255, 255, 176, 74)   # fold start
$O2  = [System.Drawing.Color]::FromArgb(255, 255, 138, 30)   # fold end
$BG1 = [System.Drawing.Color]::FromArgb(255, 21, 21, 28)     # bg top
$BG2 = [System.Drawing.Color]::FromArgb(255, 8, 8, 12)       # bg bottom
$CHIP_BG = [System.Drawing.Color]::FromArgb(255, 30, 30, 38)
$CHIP_BD = [System.Drawing.Color]::FromArgb(255, 58, 58, 72)
$SUB     = [System.Drawing.Color]::FromArgb(255, 176, 178, 188)

$TITLE = 'YouTubeApp'
$SUB_FEATURE = 'Нативный плеер и лента для Android'
$SUB_COVER   = 'Мобильное приложение: прокси, загрузка, субтитры'
$TAGLINE     = 'Нативный Android-плеер: прокси, загрузка видео, субтитры, качество до 4K'
$CHIPS4 = @('Прокси HTTP/SOCKS5', 'Скачивание видео', 'Субтитры', 'Качество до 4K')
$CHIPS3 = @('Прокси', 'Загрузка', 'Субтитры')

function New-BadgePath([float]$x, [float]$y, [float]$w, [float]$h) {
    $r = $w * 0.1625
    $c = $w * 0.1875
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddLine(($x + $r), $y, ($x + $w - $c), $y)
    $p.AddLine(($x + $w - $c), $y, ($x + $w), ($y + $c))
    $p.AddLine(($x + $w), ($y + $c), ($x + $w), ($y + $h - $r))
    $p.AddArc(($x + $w - 2 * $r), ($y + $h - 2 * $r), (2 * $r), (2 * $r), 0, 90)
    $p.AddLine(($x + $w - $r), ($y + $h), ($x + $r), ($y + $h))
    $p.AddArc($x, ($y + $h - 2 * $r), (2 * $r), (2 * $r), 90, 90)
    $p.AddLine($x, ($y + $h - $r), $x, ($y + $r))
    $p.AddArc($x, $y, (2 * $r), (2 * $r), 180, 90)
    $p.CloseFigure()
    return $p
}

function New-RoundRect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $d = $r * 2
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddLine(($x + $r), $y, ($x + $w - $r), $y)
    $p.AddArc(($x + $w - $d), $y, $d, $d, 270, 90)
    $p.AddLine(($x + $w), ($y + $r), ($x + $w), ($y + $h - $r))
    $p.AddArc(($x + $w - $d), ($y + $h - $d), $d, $d, 0, 90)
    $p.AddLine(($x + $w - $r), ($y + $h), ($x + $r), ($y + $h))
    $p.AddArc($x, ($y + $h - $d), $d, $d, 90, 90)
    $p.AddLine($x, ($y + $h - $r), $x, ($y + $r))
    $p.CloseFigure()
    return $p
}

function Draw-Badge($g, [float]$x, [float]$y, [float]$w, [float]$h) {
    $badge = New-BadgePath $x $y $w $h
    $rect = [System.Drawing.RectangleF]::new($x, $y, $w, $h)
    $grad = [System.Drawing.Drawing2D.LinearGradientBrush]::new($rect, $C1, $C2, 45.0)
    $g.FillPath($grad, $badge)
    $grad.Dispose()
    $badge.Dispose()

    # folded corner accent inside the chamfer (the YouTube modification)
    $fx = $x + $w * 0.8125
    $fw = $w - $w * 0.8125
    $foldPts = [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new($fx, $y),
        [System.Drawing.PointF]::new(($x + $w), ($y + $w * 0.1875)),
        [System.Drawing.PointF]::new($fx, ($y + $w * 0.1875))
    )
    $frect = [System.Drawing.RectangleF]::new($fx, $y, $fw, ($w * 0.1875))
    $fgrad = [System.Drawing.Drawing2D.LinearGradientBrush]::new($frect, $O1, $O2, 45.0)
    $fold = New-Object System.Drawing.Drawing2D.GraphicsPath
    $fold.AddPolygon($foldPts)
    $g.FillPath($fgrad, $fold)
    $fgrad.Dispose()
    $fold.Dispose()

    # white play triangle, rounded corners via round-join stroke
    $tri = [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(($x + $w * 0.325), ($y + $h * 0.2292)),
        [System.Drawing.PointF]::new(($x + $w * 0.325), ($y + $h * 0.7708)),
        [System.Drawing.PointF]::new(($x + $w * 0.65),  ($y + $h * 0.5))
    )
    $tp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $tp.AddPolygon($tri)
    $g.FillPath([System.Drawing.Brushes]::White, $tp)
    $pen = [System.Drawing.Pen]::new([System.Drawing.Color]::White, ($h * 0.083))
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $g.DrawPath($pen, $tp)
    $pen.Dispose()
    $tp.Dispose()
}

function New-Canvas([int]$w, [int]$h) {
    $bmp = [System.Drawing.Bitmap]::new($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
    return @($bmp, $g)
}

function Fill-Bg($g, [int]$w, [int]$h) {
    $rect = [System.Drawing.RectangleF]::new(0.0, 0.0, [float]$w, [float]$h)
    $b = [System.Drawing.Drawing2D.LinearGradientBrush]::new($rect, $BG1, $BG2, 90.0)
    $g.FillRectangle($b, 0, 0, [float]$w, [float]$h)
    $b.Dispose()
}

function Add-Glow($g, [float]$cx, [float]$cy, [float]$rad) {
    for ($i = 30; $i -ge 1; $i--) {
        $rr = $rad * $i / 30.0
        $b = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(3, 255, 46, 38))
        $g.FillEllipse($b, ($cx - $rr), ($cy - $rr), (2 * $rr), (2 * $rr))
        $b.Dispose()
    }
}

function New-Font([string]$name, [float]$size, [System.Drawing.FontStyle]$style) {
    return [System.Drawing.Font]::new($name, $size, $style, [System.Drawing.GraphicsUnit]::Pixel)
}

function Draw-TextBox($g, [string]$text, $font, $color,
                      [float]$x, [float]$y, [float]$w, [float]$h, [int]$align) {
    $fmt = [System.Drawing.StringFormat]::new()
    if ($align -eq 1) { $fmt.Alignment = [System.Drawing.StringAlignment]::Center }
    $rect = [System.Drawing.RectangleF]::new($x, $y, $w, $h)
    $brush = [System.Drawing.SolidBrush]::new($color)
    $g.DrawString($text, $font, $brush, $rect, $fmt)
    $brush.Dispose()
    $fmt.Dispose()
}

function Get-ChipWidth($g, [string]$text, $font, [float]$pad) {
    $sz = $g.MeasureString($text, $font)
    return $sz.Width + 2 * $pad
}

function Draw-Chip($g, [string]$text, $font, [float]$x, [float]$y, [float]$w, [float]$h) {
    $rr = $h / 2
    $rp = New-RoundRect $x $y $w $h $rr
    $b = [System.Drawing.SolidBrush]::new($CHIP_BG)
    $g.FillPath($b, $rp)
    $b.Dispose()
    $pen = [System.Drawing.Pen]::new($CHIP_BD, 1.0)
    $g.DrawPath($pen, $rp)
    $pen.Dispose()
    $rp.Dispose()
    Draw-TextBox $g $text $font ([System.Drawing.Color]::FromArgb(255, 232, 232, 238)) `
        $x $y $w $h 1
}

function Save-Png($bmp, [string]$path) {
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output ('saved ' + (Split-Path $path -Leaf))
}

function Draw-IconSquare([int]$size, [string]$path) {
    $c = New-Canvas $size $size
    $bmp = $c[0]; $g = $c[1]
    Fill-Bg $g $size $size
    Add-Glow $g ($size / 2.0) ($size / 2.0) ($size * 0.65)
    $bw = $size * 0.74
    $bh = $bw * 0.6
    Draw-Badge $g (($size - $bw) / 2.0) (($size - $bh) / 2.0) $bw $bh
    Save-Png $bmp $path
}

# ---------- logo set ----------
Draw-IconSquare 512 (Join-Path $logoDir 'icon-512.png')
Draw-IconSquare 192 (Join-Path $logoDir 'icon-192.png')

$c = New-Canvas 512 512
$bmp = $c[0]; $g = $c[1]
$bw = 460.0; $bh = $bw * 0.6
Draw-Badge $g ((512 - $bw) / 2.0) ((512 - $bh) / 2.0) $bw $bh
Save-Png $bmp (Join-Path $logoDir 'mark-512-transparent.png')

$c = New-Canvas 1024 320
$bmp = $c[0]; $g = $c[1]
Fill-Bg $g 1024 320
Add-Glow $g 200 160 220
$bw = 230.0; $bh = $bw * 0.6
Draw-Badge $g 60 ((320 - $bh) / 2.0) $bw $bh
$ft = New-Font 'Segoe UI' 76 ([System.Drawing.FontStyle]::Bold)
$fs = New-Font 'Segoe UI' 24 ([System.Drawing.FontStyle]::Regular)
Draw-TextBox $g $TITLE $ft ([System.Drawing.Color]::White) 340 60 640 110 0
Draw-TextBox $g 'Native Android player' $fs $SUB 340 175 640 50 0
$ft.Dispose(); $fs.Dispose()
Save-Png $bmp (Join-Path $logoDir 'logo-horizontal-1024x320.png')

# ---------- cover: Play feature graphic 1024x500 ----------
$c = New-Canvas 1024 500
$bmp = $c[0]; $g = $c[1]
Fill-Bg $g 1024 500
Add-Glow $g 320 250 300
$bw = 300.0; $bh = $bw * 0.6
Draw-Badge $g 80 ((500 - $bh) / 2.0) $bw $bh
$ft = New-Font 'Segoe UI' 64 ([System.Drawing.FontStyle]::Bold)
$fs = New-Font 'Segoe UI' 26 ([System.Drawing.FontStyle]::Regular)
$fc = New-Font 'Segoe UI' 20 ([System.Drawing.FontStyle]::Regular)
Draw-TextBox $g $TITLE $ft ([System.Drawing.Color]::White) 440 110 524 100 0
Draw-TextBox $g $SUB_FEATURE $fs $SUB 440 215 524 70 0
$cx = 440.0
foreach ($chip in $CHIPS3) {
    $w = Get-ChipWidth $g $chip $fc 22.0
    Draw-Chip $g $chip $fc $cx 340.0 $w 52.0
    $cx = $cx + $w + 14.0
}
$ft.Dispose(); $fs.Dispose(); $fc.Dispose()
Save-Png $bmp (Join-Path $coverDir 'feature-1024x500.png')

# ---------- cover: 16:9 1600x900 ----------
$c = New-Canvas 1600 900
$bmp = $c[0]; $g = $c[1]
Fill-Bg $g 1600 900
Add-Glow $g 800 380 520
$bw = 440.0; $bh = $bw * 0.6
Draw-Badge $g ((1600 - $bw) / 2.0) 140.0 $bw $bh
$ft = New-Font 'Segoe UI' 92 ([System.Drawing.FontStyle]::Bold)
$fs = New-Font 'Segoe UI' 34 ([System.Drawing.FontStyle]::Regular)
$fc = New-Font 'Segoe UI' 24 ([System.Drawing.FontStyle]::Regular)
Draw-TextBox $g $TITLE $ft ([System.Drawing.Color]::White) 0 480 1600 130 1
Draw-TextBox $g $SUB_COVER $fs $SUB 0 615 1600 70 1
$total = 0.0
$widths = @()
foreach ($chip in $CHIPS4) {
    $w = Get-ChipWidth $g $chip $fc 26.0
    $widths += ,$w
    $total = $total + $w + 16.0
}
$total = $total - 16.0
$cx = (1600 - $total) / 2.0
for ($i = 0; $i -lt $CHIPS4.Count; $i++) {
    Draw-Chip $g $CHIPS4[$i] $fc $cx 710.0 $widths[$i] 60.0
    $cx = $cx + $widths[$i] + 16.0
}
$ft.Dispose(); $fs.Dispose(); $fc.Dispose()
Save-Png $bmp (Join-Path $coverDir 'cover-1600x900.png')

# ---------- cover: GitHub banner 1584x396 ----------
$c = New-Canvas 1584 396
$bmp = $c[0]; $g = $c[1]
Fill-Bg $g 1584 396
Add-Glow $g 300 198 250
$bw = 240.0; $bh = $bw * 0.6
Draw-Badge $g 90 ((396 - $bh) / 2.0) $bw $bh
$ft = New-Font 'Segoe UI' 72 ([System.Drawing.FontStyle]::Bold)
$fs = New-Font 'Segoe UI' 30 ([System.Drawing.FontStyle]::Regular)
Draw-TextBox $g $TITLE $ft ([System.Drawing.Color]::White) 390 60 900 120 0
Draw-TextBox $g $TAGLINE $fs $SUB 390 190 1120 90 0
$ft.Dispose(); $fs.Dispose()
Save-Png $bmp (Join-Path $coverDir 'github-1584x396.png')

Write-Output 'ART_DONE'

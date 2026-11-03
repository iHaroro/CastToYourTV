# 生成 CastToYourTV 图标（各密度）+ TV Banner
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$res = 'D:\HaroroWorkSpace\castToYourTv\app\src\main\res'

function RoundedRectPath($x, $y, $w, $h, $r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function DrawIcon($size, $outFile) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'

    # 背景：深蓝紫圆角方块，带轻微渐变
    $bgPath = RoundedRectPath 0 0 $size $size ([int]($size * 0.22))
    $bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Rectangle 0, 0, $size, $size),
        [System.Drawing.Color]::FromArgb(255, 22, 32, 56),
        [System.Drawing.Color]::FromArgb(255, 10, 14, 26),
        45)
    $g.FillPath($bgBrush, $bgPath)

    $accent = [System.Drawing.Color]::FromArgb(255, 61, 220, 132)   # 绿
    $penW = [Math]::Max(2, [int]($size * 0.055))
    $pen = New-Object System.Drawing.Pen($accent, $penW)
    $pen.StartCap = 'Round'; $pen.EndCap = 'Round'

    # 投屏波纹：左下角向外扩散的两道弧线 + 实心点
    $cx = $size * 0.20; $cy = $size * 0.80   # 波纹圆心（左下）
    $r1 = $size * 0.18; $r2 = $size * 0.34
    $g.DrawArc($pen, ($cx - $r1), ($cy - $r1), ($r1 * 2), ($r1 * 2), 270, 90)
    $g.DrawArc($pen, ($cx - $r2), ($cy - $r2), ($r2 * 2), ($r2 * 2), 270, 90)
    $dotR = $size * 0.045
    $dotBrush = New-Object System.Drawing.SolidBrush($accent)
    $g.FillEllipse($dotBrush, ($cx - $dotR), ($cy - $dotR), ($dotR * 2), ($dotR * 2))

    # 屏幕：右上圆角矩形描边
    $sx = $size * 0.28; $sy = $size * 0.22; $sw = $size * 0.52; $sh = $size * 0.38
    $screenPath = RoundedRectPath $sx $sy $sw $sh ([int]($size * 0.06))
    $g.DrawPath($pen, $screenPath)

    # 播放三角：居中于屏幕内
    $triW = $sw * 0.34; $triH = $sh * 0.52
    $tx = $sx + ($sw - $triW) / 2 + $triW * 0.08
    $ty = $sy + ($sh - $triH) / 2
    $tri = New-Object System.Drawing.Point[] 3
    $tri[0] = New-Object System.Drawing.Point([int]$tx, [int]$ty)
    $tri[1] = New-Object System.Drawing.Point([int]$tx, [int]($ty + $triH))
    $tri[2] = New-Object System.Drawing.Point([int]($tx + $triW), [int]($ty + $triH / 2))
    $g.FillPolygon($dotBrush, $tri)

    $bmp.Save($outFile, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    $bgBrush.Dispose(); $pen.Dispose(); $dotBrush.Dispose(); $bgPath.Dispose(); $screenPath.Dispose()
    Write-Host "icon -> $outFile ($size)"
}

# 各密度图标
$dens = @(
    @{ d = 'mdpi';    s = 48 },
    @{ d = 'hdpi';    s = 72 },
    @{ d = 'xhdpi';   s = 96 },
    @{ d = 'xxhdpi';  s = 144 },
    @{ d = 'xxxhdpi'; s = 192 }
)
foreach ($it in $dens) {
    $dir = Join-Path $res ("mipmap-" + $it.d)
    New-Item -ItemType Directory -Force $dir | Out-Null
    DrawIcon $it.s (Join-Path $dir 'ic_launcher.png')
}

# TV Banner 320x180：深色底 + 图标缩小 + 文字
$bw = 320; $bh = 180
$bb = New-Object System.Drawing.Bitmap $bw, $bh
$bg = [System.Drawing.Graphics]::FromImage($bb)
$bg.SmoothingMode = 'AntiAlias'
$bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle 0, 0, $bw, $bh),
    [System.Drawing.Color]::FromArgb(255, 22, 32, 56),
    [System.Drawing.Color]::FromArgb(255, 10, 14, 26), 0)
$bg.FillRectangle($bgBrush, 0, 0, $bw, $bh)

# 直接画一个小图标在左侧
$tmp = Join-Path $env:TEMP 'ctyt_icon_128.png'
DrawIcon 128 $tmp
$iconImg = [System.Drawing.Image]::FromFile($tmp)
$bg.DrawImage($iconImg, 36, 40, 100, 100)
$iconImg.Dispose()

$font = New-Object System.Drawing.Font('Microsoft YaHei', 14, [System.Drawing.FontStyle]::Bold)
$textBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
$bg.DrawString('CastToYourTV', $font, $textBrush, 140, 78)

$bb.Save((Join-Path $res 'drawable\banner.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$bg.Dispose(); $bb.Dispose()
Remove-Item -Force $tmp -ErrorAction SilentlyContinue
Write-Host 'banner -> drawable/banner.png'
Write-Host 'ALL DONE'

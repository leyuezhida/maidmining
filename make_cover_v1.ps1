Add-Type -AssemblyName System.Drawing

$w = 240
$h = 150
$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'

# Background: dark cave gradient
$grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle(0, 0, $w, $h)),
    [System.Drawing.Color]::FromArgb(255, 40, 48, 60),
    [System.Drawing.Color]::FromArgb(255, 30, 26, 22),
    [System.Drawing.Drawing2D.LinearGradientMode]::Vertical)
$g.FillRectangle($grad, 0, 0, $w, $h)

# Colors
$skin = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 245, 210, 185))
$hair = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 90, 60, 40))
$dress = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 55, 70, 110))
$apron = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 245, 245, 245))
$hat = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 250, 250, 250))
$mHead = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 190, 195, 200))
$mDark = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 150, 155, 160))
$wood = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 150, 95, 45))
$edge = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(200, 220, 220, 220), 1.5)

# Maid head (skin circle)
$g.FillEllipse($skin, 96, 38, 20, 22)

# Hair
$g.FillEllipse($hair, 93, 32, 26, 18)
$g.FillEllipse($hair, 93, 42, 8, 10)
$g.FillEllipse($hair, 111, 42, 8, 10)

# Maid headdress (cap)
$g.FillRectangle($hat, 90, 28, 32, 6)
$g.FillRectangle($hat, 88, 30, 4, 10)
$g.FillRectangle($hat, 120, 30, 4, 10)

# Body (dress) - trapezoid
$dressPts = @((New-Object System.Drawing.Point(94, 64)), (New-Object System.Drawing.Point(118, 64)),
              (New-Object System.Drawing.Point(124, 108)), (New-Object System.Drawing.Point(88, 108)))
$g.FillPolygon($dress, $dressPts)

# Apron
$apronPts = @((New-Object System.Drawing.Point(97, 66)), (New-Object System.Drawing.Point(115, 66)),
              (New-Object System.Drawing.Point(118, 104)), (New-Object System.Drawing.Point(94, 104)))
$g.FillPolygon($apron, $apronPts)
$g.DrawPolygon($edge, $apronPts)

# Arms toward pickaxe
$g.FillEllipse($skin, 84, 62, 8, 16)
$g.FillEllipse($skin, 120, 62, 8, 16)

# Pickaxe held diagonally (rotated coordinate space)
$g.TranslateTransform(150, 84)
$g.RotateTransform(35)

# Handle
$g.FillRectangle($wood, -3, -38, 6, 76)
$g.DrawRectangle($edge, -3, -38, 6, 76)

# Pickaxe head
$headL = New-Object System.Drawing.Rectangle(-26, -44, 22, 12)
$headC = New-Object System.Drawing.Rectangle(-12, -52, 26, 16)
$headR = New-Object System.Drawing.Rectangle(4, -44, 22, 12)
$g.FillRectangle($mHead, $headL)
$g.DrawRectangle($edge, $headL)
$g.FillRectangle($mHead, $headC)
$g.DrawRectangle($edge, $headC)
$g.FillRectangle($mDark, $headR)
$g.DrawRectangle($edge, $headR)
$g.ResetTransform()

$bmp.Save("d:\biancheng\minecraft\forge-1.20.1-47.4.22-mdk\cover.png", [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Host "Cover v2 saved: maid with pickaxe"

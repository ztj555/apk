Add-Type -AssemblyName System.Drawing

function Create-Icon {
    param([int]$size, [string]$path)

    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'

    # 背景
    $bgBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(26, 29, 36))
    $g.FillEllipse($bgBrush, 1, 1, $size-2, $size-2)

    # 金色边框
    $penWidth = [Math]::Max(1, [int]($size/16))
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(201, 168, 76)), $penWidth
    $g.DrawEllipse($pen, 2, 2, $size-4, $size-4)

    # 电话图标
    $phoneBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(201, 168, 76))
    $scale = $size / 32.0
    $centerX = $size / 2.0
    $centerY = $size / 2.0

    # 简化电话听筒
    $points = @(
        [System.Drawing.PointF]::new($centerX - 6*$scale, $centerY - 2*$scale),
        [System.Drawing.PointF]::new($centerX + 6*$scale, $centerY + 2*$scale),
        [System.Drawing.PointF]::new($centerX + 4*$scale, $centerY + 5*$scale),
        [System.Drawing.PointF]::new($centerX + 1*$scale, $centerY + 1*$scale),
        [System.Drawing.PointF]::new($centerX - 1*$scale, $centerY - 1*$scale),
        [System.Drawing.PointF]::new($centerX - 4*$scale, $centerY + 5*$scale)
    )
    $g.FillPolygon($phoneBrush, $points)

    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

Create-Icon -size 16 -path "C:\Users\EDY\WorkBuddy\AutoDial-Extension\icons\icon16.png"
Create-Icon -size 48 -path "C:\Users\EDY\WorkBuddy\AutoDial-Extension\icons\icon48.png"
Create-Icon -size 128 -path "C:\Users\EDY\WorkBuddy\AutoDial-Extension\icons\icon128.png"
Write-Host "Icons created successfully"

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# 截取屏幕中间 800x400
$screenW = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Width
$screenH = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Height
$scanW = 800
$scanH = 400
$scanX = [math]::Max(0, ($screenW - $scanW) / 2)
$scanY = [math]::Max(0, ($screenH - $scanH) / 2)

$bmp = New-Object System.Drawing.Bitmap($scanW, $scanH)
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.CopyFromScreen($scanX, $scanY, 0, 0, (New-Object System.Drawing.Size($scanW, $scanH)))
$tmpPath = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), 'autodial_ocr.bmp')
$bmp.Save($tmpPath, [System.Drawing.Imaging.ImageFormat]::Bmp)
$gfx.Dispose()
$bmp.Dispose()
Write-Output "Screenshot: ${scanW}x${scanH} at (${scanX},${scanY}) -> $tmpPath"

# 用 C# 编译一个辅助类来调用 WinRT OCR
$csCode = @"
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Windows.Graphics.Imaging;
using Windows.Media.Ocr;
using Windows.Storage;
using Windows.Storage.Streams;

public static class OcrHelper
{
    public static string Recognize(string bmpPath)
    {
        try
        {
            var file = StorageFile.GetFileFromPathAsync(bmpPath).AsTask().GetAwaiter().GetResult();
            var stream = file.OpenAsync(FileAccessMode.Read).AsTask().GetAwaiter().GetResult();
            var decoder = BitmapDecoder.CreateAsync(stream).AsTask().GetAwaiter().GetResult();
            var softwareBitmap = decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied).AsTask().GetAwaiter().GetResult();

            OcrEngine engine = OcrEngine.TryCreateFromLanguage(new Windows.Globalization.Language("zh-Hans-CN"));
            if (engine == null)
                engine = OcrEngine.TryCreateFromUserProfileLanguages();

            var result = engine.RecognizeAsync(softwareBitmap).AsTask().GetAwaiter().GetResult();
            stream.Dispose();
            return result.Text;
        }
        catch (Exception ex)
        {
            return "ERROR:" + ex.GetType().Name + ":" + ex.Message + "|" + (ex.InnerException != null ? ex.InnerException.Message : "");
        }
    }
}
"@

try {
    Add-Type -TypeDefinition $csCode -ReferencedAssemblies System.Runtime, System.Runtime.InteropServices.WindowsRuntime -Language CSharp
    $ocrResult = [OcrHelper]::Recognize($tmpPath)
    Remove-Item $tmpPath -Force -ErrorAction SilentlyContinue
    Write-Output "=== OCR Result ==="
    Write-Output $ocrResult
    Write-Output "=== End ==="
} catch {
    Remove-Item $tmpPath -Force -ErrorAction SilentlyContinue
    Write-Output "COMPILE ERROR: $($_.Exception.Message)"
    if ($_.Exception.LoaderExceptions) {
        foreach ($le in $_.Exception.LoaderExceptions) { Write-Output "LOADER: $($le.Message)" }
    }
}

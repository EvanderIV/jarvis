# Convert audio files in Music\other-formats to MP3s and save to Music\jarvis-music

param(
    [int]$Bitrate = 192,  # Default bitrate in kbps for high quality
    [switch]$DeleteOriginals  # Optional flag to delete original files after conversion
)

# Define paths
$sourceDir = "Music\other-formats"
$outputDir = "Music\jarvis-music"

# Check if ffmpeg is installed
try {
    $ffmpegVersion = ffmpeg -version 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Error "ffmpeg is not installed or not in PATH. Please install ffmpeg."
        exit 1
    }
} catch {
    Write-Error "ffmpeg is not installed. Please install ffmpeg from https://ffmpeg.org/download.html"
    exit 1
}

# Check if source directory exists
if (-not (Test-Path $sourceDir)) {
    Write-Error "Source directory '$sourceDir' not found."
    exit 1
}

# Create output directory if it doesn't exist
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    Write-Host "Created output directory: $outputDir"
}

# Get all audio files from source directory
$audioExtensions = @("*.webm", "*.wav", "*.flac", "*.aac", "*.ogg", "*.m4a", "*.wma", "*.opus")
$files = @()
foreach ($ext in $audioExtensions) {
    $files += Get-ChildItem -Path $sourceDir -Filter $ext -File
}

if ($files.Count -eq 0) {
    Write-Host "No audio files found in '$sourceDir'"
    exit 0
}

Write-Host "Found $($files.Count) file(s) to convert" -ForegroundColor Green
Write-Host "Converting with bitrate: $($Bitrate)kbps`n"

$successCount = 0
$failureCount = 0

foreach ($file in $files) {
    $inputPath = $file.FullName
    $outputFileName = [System.IO.Path]::GetFileNameWithoutExtension($file.Name) + ".mp3"
    $outputPath = Join-Path -Path $outputDir -ChildPath $outputFileName
    
    Write-Host "Converting: $($file.Name)" -ForegroundColor Cyan
    
    # Run ffmpeg with high-quality settings
    ffmpeg -i "$inputPath" -b:a "$($Bitrate)k" -q:a 0 -y "$outputPath" 2>&1 | Out-Null
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] Converted to: $outputFileName" -ForegroundColor Green
        $successCount++
        
        # Delete original if requested
        if ($DeleteOriginals) {
            Remove-Item $inputPath -Force
            Write-Host "  [OK] Deleted original file"
        }
    } else {
        Write-Host "  [ERROR] Failed to convert $($file.Name)" -ForegroundColor Red
        $failureCount++
    }
}

# Summary
Write-Host "`n--- Conversion Summary ---" -ForegroundColor Yellow
Write-Host "Successfully converted: $successCount file(s)"
Write-Host "Failed conversions: $failureCount file(s)"
Write-Host "Output location: $(Resolve-Path $outputDir)"

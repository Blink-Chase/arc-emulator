# clean-logcat.ps1
# Usage: .\clean-logcat.ps1 [-Raw]
# Groups repetitive Arc Emulator logs in real-time or shows raw output.

param (
    [switch]$Raw
)

$lastMessage = ""
$lastTag = ""
$lastLevel = ""
$count = 1
$startTime = Get-Date

Write-Host "--- Nuclear Log Filter Starting (Arc Emulator) ---" -ForegroundColor Cyan
if ($Raw) {
    Write-Host "MODE: RAW (Show all logs)" -ForegroundColor Yellow
} else {
    Write-Host "MODE: NUCLEAR (Important bits only)" -ForegroundColor Green
}
Write-Host "Monitoring logcat stream..." -ForegroundColor Gray

adb logcat -v time | ForEach-Object {
    $line = $_
    if ($Raw) {
        Write-Host $line
        return
    }

    # Matches: 09-20 00:53:26.130 16966-17112 ArcNative com.blinkchase.arc I !!! CORE REPEAT...
    if ($line -match "^\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+-\d+\s+(.*?)\s+(.*?)\s+([VDIWE])\s+(.*)$") {
        $tag = $Matches[1].Trim()
        $level = $Matches[3]
        $msg = $Matches[4].Trim()

        # System Noise tags to drop entirely in Nuclear mode
        $noiseTags = @("View", "VRI", "HWUI", "ImeFocusController", "InputMethodManager", "InputMethodManagerUtils", "InsetsController", "ImeTracker", "InputTransport", "Kumiho-Kumiho", "ProfileInstaller", "WindowManager", "SurfaceFlinger", "WindowOrga", "BLASTBufferQueue")

        $isNoise = $false
        foreach ($noiseTag in $noiseTags) {
            if ($tag -like "*$noiseTag*") {
                $isNoise = $true
                break
            }
        }

        if ($isNoise -or $msg -like "*frameRate*" -or $msg -like "*getPackageName*" -or $msg -like "*mWNT*") {
            return
        }

        # Only group noisy Arc logs or repetition markers
        $isNoisyTag = ($tag -eq "Arc" -or $tag -eq "ArcNative" -or $tag -eq "ArcAudio")

        if ($isNoisyTag -and $msg -eq $lastMessage -and $tag -eq $lastTag -and $level -eq $lastLevel) {
            $count++
        } else {
            # Emit the previous group if it existed
            if ($lastMessage -ne "") {
                $color = "Gray"
                if ($lastLevel -eq "E") { $color = "Red" }
                elseif ($lastLevel -eq "W") { $color = "Yellow" }
                elseif ($lastLevel -eq "I") { $color = "Green" }

                $countStr = if ($count -gt 1) { " [x$count]" } else { "" }
                $timeStr = if ($count -gt 1) { " (Duration: $(([DateTime]::Now - $startTime).TotalSeconds.ToString("F1"))s)" } else { "" }

                Write-Host "$lastLevel/$lastTag: $lastMessage$countStr$timeStr" -ForegroundColor $color
            }

            # Reset for new message
            $lastMessage = $msg
            $lastTag = $tag
            $lastLevel = $level
            $count = 1
            $startTime = Get-Date
        }
    } else {
        # Fallback for lines that don't match our specific pattern but aren't noise
        Write-Host $line -ForegroundColor DarkGray
    }
}

param([Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference = 'Stop'
if ($env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') { throw 'Hosted Windows only' }
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
Add-Type -AssemblyName System.Speech
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $english = $speaker.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'en' } | Select-Object -First 1
    $speaker.SelectVoice($english.VoiceInfo.Name)
    $format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    foreach ($name in @('Enderman','Pikachu','Lopunny')) {
        $stream = New-Object System.IO.MemoryStream
        try {
            $speaker.SetOutputToAudioStream($stream,$format)
            $speaker.Speak($name)
            $speaker.SetOutputToNull()
            $data = [byte[]]::new(640 * 3 + $stream.Length + 640 * 28)
            [Array]::Copy($stream.ToArray(),0,$data,640 * 3,$stream.Length)
            [IO.File]::WriteAllBytes((Join-Path $Destination ($name.ToLowerInvariant()+'.pcm')),$data)
        } finally { $stream.Dispose() }
    }
} finally { $speaker.Dispose() }

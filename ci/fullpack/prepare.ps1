$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') { throw 'Hosted Windows only' }
# Reuse the verified software renderer and existing Windows SAPI fixture generation.
$env:MOBSEAR_POKEMON_ACOUSTIC = '1'
& "$PSScriptRoot/../windows-world/prepare.ps1"
$root = Join-Path $env:RUNNER_TEMP 'hearing-windows'
Add-Type -AssemblyName System.Speech
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $english = $voice.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'en' } | Select-Object -First 1
    $voice.SelectVoice($english.VoiceInfo.Name)
    $format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    $stream = New-Object System.IO.MemoryStream
    $voice.SetOutputToAudioStream($stream, $format)
    $voice.Speak('Enderman')
    $voice.SetOutputToNull()
    $pcm = $stream.ToArray()
    $stream.Dispose()
    $data = [byte[]]::new(640 * 3 + $pcm.Length + 640 * 28)
    [Array]::Copy($pcm, 0, $data, 640 * 3, $pcm.Length)
    [IO.File]::WriteAllBytes((Join-Path $root 'synth/enderman.pcm'), $data)
} finally { $voice.Dispose() }

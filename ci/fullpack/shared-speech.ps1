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

# Use the established pronunciation corpus for a second voice with a different vowel.
$variants = Join-Path $env:RUNNER_TEMP 'comparison-pronunciations'
New-Item -ItemType Directory -Path $variants -Force | Out-Null
& "$PSScriptRoot/../speech-focus/make.ps1" $variants
$manifest = Import-Csv -LiteralPath (Join-Path $variants 'sources.tsv') -Delimiter ([char]9) -Header File,Voice,Phrase,Rate,Expected
foreach ($pair in @(@('ender-unstressed','enderman-alternate'),@('low-punny','lopunny-alternate'),@('hold-on','hold-on'))) {
    $row = $manifest | Where-Object { $_.Phrase -eq $pair[0] -and $_.Rate -eq '0' } | Select-Object -Last 1
    if ($null -eq $row) { throw 'Missing second-voice pronunciation fixture' }
    $raw = [IO.File]::ReadAllBytes((Join-Path $variants ($row.File+'.raw')))
    $padded = [byte[]]::new(640 * 31 + $raw.Length)
    [Array]::Copy($raw,0,$padded,640 * 3,$raw.Length)
    [IO.File]::WriteAllBytes((Join-Path $Destination ($pair[1]+'.pcm')),$padded)
    Write-Output ("Generated " + $pair[1] + " with " + $row.Voice)
}
$silence = [byte[]]::new(32000)
[IO.File]::WriteAllBytes((Join-Path $Destination 'silence.pcm'), $silence)
$fan = [byte[]]::new(32000 + 6400)
for ($i = 0; $i -lt 16000; $i++) {
    $sample = [int16][Math]::Round(300 * [Math]::Sin(2 * [Math]::PI * 100 * $i / 16000))
    [BitConverter]::GetBytes($sample).CopyTo($fan, 2 * $i)
}
[IO.File]::WriteAllBytes((Join-Path $Destination 'fan.pcm'), $fan)

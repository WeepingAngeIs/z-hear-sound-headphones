# Hosted synthetic input and process-local software graphics for the Windows game client.
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows') { throw 'Hosted Windows only' }
$testRoot = Join-Path $env:RUNNER_TEMP 'hearing-windows'
$audioDir = Join-Path $testRoot 'synth'
New-Item -ItemType Directory -Path $audioDir -Force | Out-Null
Add-Type -AssemblyName System.Speech
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
$english = $voice.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'en' } | Select-Object -First 1
$voice.SelectVoice($english.VoiceInfo.Name)
$format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$index = 0
$alternatives = @{'watch and roll'='wattrel'; 'kilo watch roll'='kilowattrel'; 'Vivian'='vivillon'}
$words = if ($env:MOBSEAR_POKEMON_ACOUSTIC -eq '1') { @('Pikachu', 'Eevee', 'Lopunny') + @($alternatives.Keys) } else { @('Enderman', 'spider', 'mob', 'mobs') }
if ($env:MOBSEAR_OWL_ACOUSTIC -eq '1') { $words += @('oo woo guardian', 'call oo woo guardian') }
foreach ($word in $words) {
    $stream = New-Object System.IO.MemoryStream
    $voice.SetOutputToAudioStream($stream, $format)
    $voice.Speak($word)
    $voice.SetOutputToNull()
    $pcm = $stream.ToArray()
    $data = [byte[]]::new(640 * 3 + $pcm.Length + 640 * 28)
    [Array]::Copy($pcm, 0, $data, 640 * 3, $pcm.Length)
    $stem = if ($env:MOBSEAR_POKEMON_ACOUSTIC -eq '1') { $word.ToLowerInvariant() } else { [string]$index }
    if ($word -eq 'oo woo guardian') { $stem = 'owl-guardian' }
    if ($word -eq 'call oo woo guardian') { $stem = 'owl-guardian-explicit' }
    if ($alternatives.ContainsKey($word)) { $stem = $alternatives[$word] }
    [IO.File]::WriteAllBytes((Join-Path $audioDir "$stem.pcm"), $data)
    $index++
    $stream.Dispose()
}
$voice.Dispose()
$archive = Join-Path $testRoot 'mesa.7z'
$graphics = Join-Path $testRoot 'graphics'
curl.exe -fsSL --retry 3 'https://github.com/pal1000/mesa-dist-win/releases/download/25.1.9/mesa3d-25.1.9-release-msvc.7z' -o $archive
if ($LASTEXITCODE -ne 0 -or (Get-FileHash $archive).Hash.ToLowerInvariant() -ne 'dd83c3c4cc4d7706c9e21b277388447a2fc5367b0912f9ee45621e36aeb3b7d2') { throw 'Graphics package digest mismatch' }
& 7z x -y "-o$graphics" $archive | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Unable to unpack renderer' }
$driver = Get-ChildItem -LiteralPath $graphics -Filter 'opengl32.dll' -Recurse | Where-Object { $_.Directory.Name -eq 'x64' } | Select-Object -First 1
if (-not $driver) { throw 'Missing x64 software renderer' }
# GLFW loads opengl32 relative to its process executable. This job's temporary JDK is discarded.
Copy-Item -Path (Join-Path $driver.Directory.FullName '*.dll') -Destination (Join-Path $env:JAVA_HOME 'bin')
"MOBSEAR_SMOKE_DIR=$testRoot" | Out-File $env:GITHUB_ENV -Encoding utf8 -Append
"MOBSEAR_POKEMON_ROOT=$testRoot" | Out-File $env:GITHUB_ENV -Encoding utf8 -Append
"MOBSEAR_POKEMON_PCM=$audioDir" | Out-File $env:GITHUB_ENV -Encoding utf8 -Append
"MOBSEAR_SYNTH_DEVICE=$audioDir" | Out-File $env:GITHUB_ENV -Encoding utf8 -Append

# This helper receives short, length-prefixed PCM utterances on stdin. Its only
# output is a binary readiness byte followed by one numeric decision per request.
$ErrorActionPreference = 'Stop'
$engine = $null
try {
    Add-Type -AssemblyName System.Speech
    $english = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers() |
        Where-Object {
            $_.Culture.TwoLetterISOLanguageName -eq 'en' -and $_.Id -match '^MS-[0-9]+-[0-9]+-DESK$'
        } |
        Select-Object -First 1
    if ($null -eq $english) { exit 2 }
    $engine = [System.Speech.Recognition.SpeechRecognitionEngine]::new($english)
    # Disable acoustic adaptation on this in-process engine only.
    $engine.UpdateRecognizerSetting('AdaptationOn', [int]0)

    $aliases = @{
        'zombie' = 1; 'zombies' = 1; 'zombified piglin' = 1; 'zombified piglins' = 1
        'mobs' = 2
        'enderman' = 3; 'endermen' = 3; 'ender man' = 3; 'ender men' = 3
    }
    $phrases = [System.Speech.Recognition.Choices]::new()
    foreach ($alias in $aliases.Keys) {
        $phrases.Add([string]$alias)
        $phrases.Add('hey ' + $alias)
    }
    # Give a standalone greeting a direct match with no mapped decision.
    $phrases.Add('hey')
    $builder = [System.Speech.Recognition.GrammarBuilder]::new()
    $builder.Culture = $english.Culture
    $builder.Append($phrases)
    $calls = [System.Speech.Recognition.Grammar]::new($builder)
    $calls.Name = 'mob call'
    $calls.Priority = 1
    $engine.LoadGrammar($calls)
    # Unrelated speech can match the desktop vocabulary. Its text is never read.
    $engine.LoadGrammar([System.Speech.Recognition.DictationGrammar]::new())

    $format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(
        16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
        [System.Speech.AudioFormat.AudioChannel]::Mono)
    $reader = [System.IO.BinaryReader]::new([Console]::OpenStandardInput())
    $output = [Console]::OpenStandardOutput()
    $output.WriteByte(82)
    $output.Flush()
    while ($true) {
        try { $length = $reader.ReadInt32() } catch [System.IO.EndOfStreamException] { break }
        if ($length -le 0 -or $length -gt 96000 -or $length % 2 -ne 0) { exit 3 }
        $pcm = $reader.ReadBytes($length)
        if ($pcm.Length -ne $length) { exit 3 }
        $stream = [System.IO.MemoryStream]::new($pcm, $false)
        $decision = 0
        try {
            $engine.SetInputToAudioStream($stream, $format)
            $result = $engine.Recognize()
            if ($null -ne $result -and $result.Grammar.Name -eq 'mob call' -and $result.Confidence -ge 0.72) {
                $phrase = $result.Text.ToLowerInvariant()
                if ($phrase.StartsWith('hey ')) { $phrase = $phrase.Substring(4) }
                if ($aliases.ContainsKey($phrase)) { $decision = $aliases[$phrase] }
            }
        } finally {
            $engine.SetInputToNull()
            $stream.Dispose()
            [Array]::Clear($pcm, 0, $length)
            $result = $null
        }
        $output.WriteByte([byte]$decision)
        $output.Flush()
    }
} catch {
    exit 1
} finally {
    if ($null -ne $engine) { $engine.Dispose() }
}

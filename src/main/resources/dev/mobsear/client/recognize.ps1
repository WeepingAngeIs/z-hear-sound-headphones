# The local binary pipe carries a bounded grammar followed by short PCM clips.
# Only a readiness byte and one unsigned 16-bit decision per request leave the worker.
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

    $reader = [System.IO.BinaryReader]::new([Console]::OpenStandardInput())
    $size = $reader.ReadInt32()
    if ($size -le 0 -or $size -gt 1048576) { exit 3 }
    $vocabulary = $reader.ReadBytes($size)
    if ($vocabulary.Length -ne $size) { exit 3 }
    $aliases = @{}
    $confirm = @{}
    $words = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in [Text.Encoding]::ASCII.GetString($vocabulary).Split([char]10)) {
        if ($entry.Length -eq 0) { continue }
        $parts = $entry.Split(':')
        if ($parts.Count -ne 3) { exit 3 }
        $aliases.Add($parts[2], [uint16]$parts[0])
        if ($parts[1] -eq '1') { $confirm.Add($parts[2], $true) }
        $words.Add($parts[2])
        $words.Add('hey ' + $parts[2])
    }
    $phrases = [System.Speech.Recognition.Choices]::new()
    # Explicit rejection phrases give greetings and generic collective calls a harmless match.
    foreach ($ignored in @('hey', 'pokemon', 'hey pokemon', 'all pokemon', 'every pokemon')) {
        if (-not $aliases.ContainsKey($ignored)) { $words.Add($ignored) }
    }
    $phrases.Add($words.ToArray())
    $builder = [System.Speech.Recognition.GrammarBuilder]::new()
    $builder.Culture = $english.Culture
    $builder.Append($phrases)
    $calls = [System.Speech.Recognition.Grammar]::new($builder)
    $calls.Name = 'mob call'
    $calls.Priority = 1
    $engine.LoadGrammar($calls)
    # The background vocabulary can reject ordinary speech. No transcript leaves this process.
    $background = [System.Speech.Recognition.DictationGrammar]::new()
    $engine.LoadGrammar($background)

    $format = [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(
        16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
        [System.Speech.AudioFormat.AudioChannel]::Mono)
    # Exercise the newly loaded grammars before accepting the player's first utterance.
    $warmup = [System.IO.MemoryStream]::new([byte[]]::new(16000), $false)
    $engine.SetInputToAudioStream($warmup, $format)
    $null = $engine.Recognize()
    $engine.SetInputToNull()
    $warmup.Dispose()
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
            $minConfidence = 0.58
            # Rescore a rejection or very weak dictation hypothesis with the command grammar.
            # Keep ordinary speech with a credible match in the background vocabulary.
            if ($null -eq $result -or ($result.Grammar.Name -ne 'mob call' -and $result.Confidence -lt 0.12)) {
                $engine.SetInputToNull()
                $stream.Dispose()
                $stream = [System.IO.MemoryStream]::new($pcm, $false)
                $background.Enabled = $false
                try {
                    $engine.SetInputToAudioStream($stream, $format)
                    $result = $engine.Recognize()
                } finally {
                    $background.Enabled = $true
                }
                $minConfidence = 0.72
            }
            if ($null -ne $result -and $result.Grammar.Name -eq 'mob call' -and $result.Confidence -ge $minConfidence) {
                $phrase = $result.Text.ToLowerInvariant()
                if ($phrase.StartsWith('hey ')) { $phrase = $phrase.Substring(4) }
                if ($aliases.ContainsKey($phrase)) {
                    $candidate = $aliases[$phrase]
                    if (-not $confirm.ContainsKey($phrase)) {
                        $decision = $candidate
                    } else {
                        # The command grammar can confidently confuse close names. Require
                        # the independent desktop vocabulary to agree, or accept the separately
                        # registered explicit '<name> ending <letters>' command on a later call.
                        $engine.SetInputToNull()
                        $stream.Dispose()
                        $stream = [System.IO.MemoryStream]::new($pcm, $false)
                        $calls.Enabled = $false
                        try {
                            $engine.SetInputToAudioStream($stream, $format)
                            $second = $engine.Recognize()
                            if ($null -ne $second -and $second.Confidence -ge 0.58) {
                                $key = $second.Text.ToLowerInvariant() -replace '^hey ', ''
                                if ($aliases.ContainsKey($key) -and $aliases[$key] -eq $candidate) { $decision = $candidate }
                            }
                        } finally {
                            $engine.SetInputToNull()
                            $calls.Enabled = $true
                            $second = $null
                            $key = $null
                        }
                    }
                }
            }
        } finally {
            $engine.SetInputToNull()
            $stream.Dispose()
            [Array]::Clear($pcm, 0, $length)
            $result = $null
        }
        $output.WriteByte([byte]($decision -shr 8))
        $output.WriteByte([byte]($decision -band 255))
        $output.Flush()
    }
} catch {
    exit 1
} finally {
    if ($null -ne $engine) { $engine.Dispose() }
}

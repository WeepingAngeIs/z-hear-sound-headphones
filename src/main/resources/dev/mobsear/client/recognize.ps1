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
    if ($size -le 0 -or $size -gt 8388608) { exit 3 }
    $vocabulary = $reader.ReadBytes($size)
    if ($vocabulary.Length -ne $size) { exit 3 }
    $aliases = @{}
    $confirm = @{}
    $wordBreaks = @{}
    $words = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in [Text.Encoding]::ASCII.GetString($vocabulary).Split([char]10)) {
        if ($entry.Length -eq 0) { continue }
        $parts = $entry.Split(':')
        if ($parts.Count -ne 3) { exit 3 }
        $aliases.Add($parts[2], [uint16]$parts[0])
        $words.Add('call ' + $parts[2])
        if ($parts[1] -eq '2') {
            $wordBreaks.Add($parts[2], $true)
            continue
        }
        if ($parts[1] -eq '1') { $confirm.Add($parts[2], $true) }
        $words.Add($parts[2])
        $words.Add('hey ' + $parts[2])
    }
    $phrases = [System.Speech.Recognition.Choices]::new()
    # Explicit rejection phrases give greetings and generic collective calls a harmless match.
    foreach ($ignored in @('hey', 'call', 'pokemon', 'hey pokemon', 'call pokemon', 'all pokemon', 'every pokemon')) {
        if (-not $aliases.ContainsKey($ignored)) { $words.Add($ignored) }
    }
    $phrases.Add($words.ToArray())
    $builder = [System.Speech.Recognition.GrammarBuilder]::new()
    $builder.Culture = $english.Culture
    $builder.Append($phrases)
    if ($english.Culture.Name -eq 'en-US') {
        # Fictional compound names have several pronunciations. Keep their written result,
        # ID and confidence checks identical for all pronunciations of the same name.
        $lexicon = [ordered]@{
            enderman = @('eh n d er m ae n', 'eh n d er m ax n')
            lopunny = @('l aa p ax n iy', 'l aa p ah n iy', 'l ow p ah n iy', 'l aa p b ah n iy')
        }
        $document = [System.Speech.Recognition.SrgsGrammar.SrgsDocument]::new($builder)
        $document.PhoneticAlphabet = [System.Speech.Recognition.SrgsGrammar.SrgsPhoneticAlphabet]::Sapi
        $choice = [System.Speech.Recognition.SrgsGrammar.SrgsOneOf]::new()
        $existing = [System.Speech.Recognition.SrgsGrammar.SrgsRuleRef]::new($document.Root)
        $choice.Add([System.Speech.Recognition.SrgsGrammar.SrgsItem]::new($existing))
        foreach ($name in $lexicon.Keys) {
            if (-not $aliases.ContainsKey($name) -or $aliases[$name] -eq 0) { continue }
            foreach ($phones in $lexicon[$name]) {
                foreach ($prefix in @('', 'hey', 'call')) {
                    $item = [System.Speech.Recognition.SrgsGrammar.SrgsItem]::new()
                    if ($prefix) { $item.Add([System.Speech.Recognition.SrgsGrammar.SrgsText]::new($prefix)) }
                    $token = [System.Speech.Recognition.SrgsGrammar.SrgsToken]::new($name)
                    $token.Pronunciation = $phones
                    $item.Add($token)
                    $choice.Add($item)
                }
            }
        }
        $root = [System.Speech.Recognition.SrgsGrammar.SrgsRule]::new('RegisteredCalls')
        $root.Add($choice)
        $document.Rules.Add($root)
        $document.Root = $root
        $calls = [System.Speech.Recognition.Grammar]::new($document)
    } else {
        $calls = [System.Speech.Recognition.Grammar]::new($builder)
    }
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
                $minConfidence = if ($aliases.Count -gt 256) { 0.90 } else { 0.72 }
            }
            if ($null -ne $result -and $result.Grammar.Name -eq 'mob call' -and $result.Confidence -ge $minConfidence) {
                $phrase = $result.Text.ToLowerInvariant()
                if ($phrase.StartsWith('call ')) {
                    if ($result.Confidence -lt 0.82) {
                        # Only an explicitly enunciated compound can use this extra check.
                        # A weak ordinary call must stay rejected even if rescoring repeats it.
                        $engine.SetInputToNull()
                        $stream.Dispose()
                        $stream = [System.IO.MemoryStream]::new($pcm, $false)
                        $background.Enabled = $false
                        try {
                            $engine.SetInputToAudioStream($stream, $format)
                            $verified = $engine.Recognize()
                            $firstName = $phrase.Substring(5)
                            $again = if ($null -ne $verified) { $verified.Text.ToLowerInvariant() } else { '' }
                            if ($null -eq $verified -or $verified.Confidence -lt 0.92 -or
                                    $verified.Grammar.Name -ne 'mob call' -or -not $again.StartsWith('call ') -or
                                    -not $aliases.ContainsKey($firstName) -or -not $aliases.ContainsKey($again.Substring(5)) -or
                                    $aliases[$firstName] -ne $aliases[$again.Substring(5)] -or
                                    (-not $wordBreaks.ContainsKey($firstName) -and -not $wordBreaks.ContainsKey($again.Substring(5)))) {
                                $phrase = ''
                            }
                        } finally {
                            $engine.SetInputToNull()
                            $background.Enabled = $true
                            $verified = $null
                        }
                    }
                    if ($phrase.Length -gt 0) { $phrase = $phrase.Substring(5) }
                }
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
                                $key = $second.Text.ToLowerInvariant() -replace '^(hey|call) ', ''
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

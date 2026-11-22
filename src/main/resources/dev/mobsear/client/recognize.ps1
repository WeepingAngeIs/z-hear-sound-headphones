# The local binary pipe carries a bounded grammar followed by short PCM clips.
# Only a readiness byte and one unsigned 16-bit decision per request leave the worker.
$ErrorActionPreference = 'Stop'
$engine = $null
$pronouncer = $null
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
    $calls = [System.Speech.Recognition.Grammar]::new($builder)
    $calls.Name = 'mob call'
    $calls.Priority = 1
    $engine.LoadGrammar($calls)
    # The background vocabulary can reject ordinary speech. No transcript leaves this process.
    $background = [System.Speech.Recognition.DictationGrammar]::new()
    $engine.LoadGrammar($background)

    if ($english.Culture.Name -eq 'en-US') {
        # Verify alternate pronunciations in a separate engine so they cannot displace
        # thousands of existing registry commands or change the original engine's confidence.
        $lexicon = [ordered]@{
            enderman = @('eh n d er m ae n', 'eh n d er m ax n')
            lopunny = @('l aa p ax n iy', 'l aa p ah n iy', 'l ow p ah n iy', 'l aa p b ah n iy')
            hoglin = @('h aa g l ih n', 'h ao g l ih n')
            zoglin = @('z aa g l ih n', 'z ao g l ih n')
        }
        try {
            $choice = [System.Speech.Recognition.SrgsGrammar.SrgsOneOf]::new()
            # Keep near neighbors as separate alternatives alongside unconstrained dictation.
            foreach ($neighbor in @('poponee', 'buneary', 'endermite', 'ender dragon', 'ender guardian',
                    'slender man', 'bunny', 'hold on', 'logged in', 'huddling', 'jogging')) {
                $choice.Add([System.Speech.Recognition.SrgsGrammar.SrgsItem]::new($neighbor))
            }
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
            $root = [System.Speech.Recognition.SrgsGrammar.SrgsRule]::new('Pronunciations')
            $root.Add($choice)
            $document = [System.Speech.Recognition.SrgsGrammar.SrgsDocument]::new($root)
            $document.Culture = $english.Culture
            $document.PhoneticAlphabet = [System.Speech.Recognition.SrgsGrammar.SrgsPhoneticAlphabet]::Sapi
            $pronounced = [System.Speech.Recognition.Grammar]::new($document)
            $pronouncer = [System.Speech.Recognition.SpeechRecognitionEngine]::new($english)
            $pronouncer.UpdateRecognizerSetting('AdaptationOn', [int]0)
            $pronouncer.LoadGrammar($pronounced)
            $pronunciationBackground = [System.Speech.Recognition.DictationGrammar]::new()
            $pronouncer.LoadGrammar($pronunciationBackground)
        } catch {
            # The ordinary command grammar stays usable if the optional US voice data is unavailable.
            if ($null -ne $pronouncer) { $pronouncer.Dispose() }
            $pronouncer = $null
        }
    }

    function Get-QuietBuffer([byte[]]$source) {
        $samples = [int16[]]::new($source.Length / 2)
        try {
            [Buffer]::BlockCopy($source, 0, $samples, 0, $source.Length)
            [int]$peak = 0
            foreach ($sample in $samples) { $peak = [Math]::Max($peak, [Math]::Abs([int]$sample)) }
            if ($peak -lt 3000) { return $null }
            $gain = 2300.0 / $peak
            for ($i = 0; $i -lt $samples.Length; $i++) { $samples[$i] = [int16]($samples[$i] * $gain) }
            $bytes = [byte[]]::new($source.Length)
            [Buffer]::BlockCopy($samples, 0, $bytes, 0, $bytes.Length)
            return ,$bytes
        } finally {
            [Array]::Clear($samples, 0, $samples.Length)
        }
    }

    function Confirm-LevelMatch([string]$proposed, [string]$utterance, [byte[]]$source, $elapsed) {
        $quiet = Get-QuietBuffer $source
        if ($null -eq $quiet) { return $false }
        $quietStream = [System.IO.MemoryStream]::new($quiet, $false)
        $catalogStream = $null
        try {
            $pronunciationBackground.Enabled = $false
            $pronouncer.SetInputToAudioStream($quietStream, $format)
            $phonetic = $pronouncer.Recognize()
            if ($null -eq $phonetic -or $phonetic.Confidence -lt 0.90 -or
                    $phonetic.Text -ne $utterance -or $elapsed.ElapsedMilliseconds -ge 1200) { return $false }
            $engine.SetInputToNull()
            $catalogStream = [System.IO.MemoryStream]::new($quiet, $false)
            $background.Enabled = $false
            $engine.SetInputToAudioStream($catalogStream, $format)
            $catalogMatch = $engine.Recognize()
            if ($null -eq $catalogMatch -or $catalogMatch.Grammar -ne $calls -or $catalogMatch.Confidence -lt 0.66) { return $false }
            $catalogName = $catalogMatch.Text.ToLowerInvariant() -replace '^(hey|call) ', ''
            return $aliases.ContainsKey($catalogName) -and $aliases[$catalogName] -eq $aliases[$proposed]
        } finally {
            if ($null -ne $catalogStream) {
                $engine.SetInputToNull()
                $background.Enabled = $true
                $catalogStream.Dispose()
            }
            $pronouncer.SetInputToNull()
            $pronunciationBackground.Enabled = $true
            $quietStream.Dispose()
            [Array]::Clear($quiet, 0, $quiet.Length)
        }
    }

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
        $elapsed = [Diagnostics.Stopwatch]::StartNew()
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
            # Check only rejections, the measured Poponee/Lopunny collision, and a positive Hoglin.
            # Suppress unconfirmed Hoglin and conflicting species decisions during ordinary speech.
            if ($null -ne $pronouncer -and ($decision -eq 0 -or $decision -eq $aliases['poponee'] -or $decision -eq $aliases['hoglin'])) {
                $checkStream = [System.IO.MemoryStream]::new($pcm, $false)
                try {
                    $pronouncer.SetInputToAudioStream($checkStream, $format)
                    $match = $pronouncer.Recognize()
                    $name = if ($null -ne $match) { $match.Text.ToLowerInvariant() -replace '^(hey|call) ', '' } else { '' }
                    $confident = $null -ne $match -and $match.Grammar -eq $pronounced -and $match.Confidence -ge 0.83
                    # Recheck an incomplete decision against explicit pronunciations AND near neighbors.
                    # A constrained winner alone is insufficient. Require matching evidence from
                    # the independent full catalog or the pronunciation grammar competing with dictation.
                    if (-not $confident) {
                        $pronouncer.SetInputToNull()
                        $checkStream.Dispose()
                        $checkStream = [System.IO.MemoryStream]::new($pcm, $false)
                        $pronunciationBackground.Enabled = $false
                        try {
                            $pronouncer.SetInputToAudioStream($checkStream, $format)
                            $restricted = $pronouncer.Recognize()
                        } finally {
                            $pronouncer.SetInputToNull()
                            $pronunciationBackground.Enabled = $true
                        }
                        if ($null -ne $restricted -and $restricted.Grammar -eq $pronounced) {
                            $proposed = $restricted.Text.ToLowerInvariant() -replace '^(hey|call) ', ''
                            $primaryName = if ($null -ne $result -and $result.Grammar -eq $calls) {
                                $result.Text.ToLowerInvariant() -replace '^(hey|call) ', ''
                            } else { '' }
                            if ($lexicon.Contains($proposed) -and $aliases.ContainsKey($proposed)) {
                                $samePrimary = $aliases.ContainsKey($primaryName) -and $aliases[$primaryName] -eq $aliases[$proposed]
                                $sameOpen = $null -ne $match -and $match.Grammar -eq $pronounced -and $name -eq $proposed
                                if (($samePrimary -and $result.Confidence -ge 0.82 -and $restricted.Confidence -ge 0.74) -or
                                        ($samePrimary -and $result.Confidence -ge 0.68 -and $restricted.Confidence -ge 0.90) -or
                                        ($sameOpen -and $match.Confidence -ge 0.70 -and $restricted.Confidence -ge 0.90)) {
                                    $confident = $true
                                    $name = $proposed
                                }
                                # Only spend two extra short passes on a plausible missed name. Require
                                # agreement between different vocabularies at the common input level.
                                # A forced match in the small grammar alone cannot rescue a rejection.
                                $canRetry = -not $confident -and $decision -eq 0 -and $proposed -in @('lopunny', 'enderman') -and
                                        $restricted.Confidence -ge 0.62 -and $elapsed.ElapsedMilliseconds -lt 800 -and
                                        -not ($null -ne $match -and $match.Grammar -is [System.Speech.Recognition.DictationGrammar] -and $match.Confidence -ge 0.30)
                                if ($canRetry -and (Confirm-LevelMatch $proposed $restricted.Text $pcm $elapsed)) {
                                    $confident = $true
                                    $name = $proposed
                                }
                            }
                        }
                        $restricted = $null
                    }
                    if ($decision -eq $aliases['hoglin'] -and (-not $confident -or $name -ne 'hoglin')) {
                        $decision = 0
                    } elseif ($decision -eq 0 -and $confident -and $lexicon.Contains($name) -and $aliases.ContainsKey($name)) {
                        $decision = $aliases[$name]
                    } elseif ($decision -eq $aliases['poponee'] -and $confident -and $name -eq 'lopunny') {
                        $decision = 0
                    }
                } finally {
                    $pronouncer.SetInputToNull()
                    $checkStream.Dispose()
                    $match = $null
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
    if ($null -ne $pronouncer) { $pronouncer.Dispose() }
}

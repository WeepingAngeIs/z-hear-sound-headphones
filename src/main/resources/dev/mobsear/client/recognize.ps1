# The local binary pipe carries a bounded grammar followed by short PCM clips.
# The ordinary reply is one unsigned 16-bit decision. Opt-in local tests can request a short rejected guess.
$ErrorActionPreference = 'Stop'
$engine = $null
$pronouncer = $null
try {
    $reader = [System.IO.BinaryReader]::new([Console]::OpenStandardInput())
    $output = [Console]::OpenStandardOutput()
    $tracked = $env:MOBSEAR_STARTUP_STEPS -eq '1'
    if ($tracked) { $startup = [Console]::OpenStandardError() }
    function Send-StartupStep([byte]$step) {
        if ($tracked) { $startup.WriteByte($step); $startup.Flush() }
    }
    Send-StartupStep 81
    $size = $reader.ReadInt32()
    if ($size -le 0 -or $size -gt 8388608) { exit 3 }
    $vocabulary = $reader.ReadBytes($size)
    if ($vocabulary.Length -ne $size) { exit 3 }
    Send-StartupStep 71
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
    Send-StartupStep 69
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
    Send-StartupStep 76

    $lexicon = [ordered]@{}
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

    # A second grammar has one registered name between optional passages of real dictation.
    # It is disabled during the original short-command pass and shares the same local engine.
    $nameItems = [System.Speech.Recognition.SrgsGrammar.SrgsOneOf]::new()
    foreach ($label in $aliases.Keys) {
        if ($aliases[$label] -eq 0 -or $wordBreaks.ContainsKey($label)) { continue }
        $item = [System.Speech.Recognition.SrgsGrammar.SrgsItem]::new($label)
        $item.Add([System.Speech.Recognition.SrgsGrammar.SrgsNameValueTag]::new('mob', $label))
        $nameItems.Add($item)
        if ($lexicon.Contains($label)) {
            foreach ($phones in $lexicon[$label]) {
                $token = [System.Speech.Recognition.SrgsGrammar.SrgsToken]::new($label)
                $token.Pronunciation = $phones
                $item = [System.Speech.Recognition.SrgsGrammar.SrgsItem]::new()
                $item.Add($token)
                $item.Add([System.Speech.Recognition.SrgsGrammar.SrgsNameValueTag]::new('mob', $label))
                $nameItems.Add($item)
            }
        }
    }
    $contextRule = [System.Speech.Recognition.SrgsGrammar.SrgsRule]::new('Mention')
    $prefixWords = [System.Speech.Recognition.SrgsGrammar.SrgsItem]::new(0,1)
    $prefixWords.Add([System.Speech.Recognition.SrgsGrammar.SrgsRuleRef]::Dictation)
    $suffixWords = [System.Speech.Recognition.SrgsGrammar.SrgsItem]::new(0,1)
    $suffixWords.Add([System.Speech.Recognition.SrgsGrammar.SrgsRuleRef]::Dictation)
    $contextRule.Add($prefixWords)
    $contextRule.Add($nameItems)
    $contextRule.Add($suffixWords)
    $contextDocument = [System.Speech.Recognition.SrgsGrammar.SrgsDocument]::new($contextRule)
    $contextDocument.Culture = $english.Culture
    $contextDocument.PhoneticAlphabet = [System.Speech.Recognition.SrgsGrammar.SrgsPhoneticAlphabet]::Sapi
    $sentence = [System.Speech.Recognition.Grammar]::new($contextDocument)
    $sentence.Name = 'name in speech'
    $sentence.Enabled = $false
    $engine.LoadGrammar($sentence)

    function Find-Mention($plain, [byte[]]$samples) {
        if ($null -eq $plain -or $plain.Words.Count -lt 2) { return 0 }
        $audio = [IO.MemoryStream]::new($samples, $false)
        $engine.SetInputToNull()
        try {
            $sentence.Enabled = $true
            $calls.Enabled = $false
            $engine.SetInputToAudioStream($audio,$format)
            $mention = $engine.Recognize()
            if ($null -eq $mention -or $mention.Grammar.Name -ne 'name in speech' -or
                    -not $mention.Semantics.ContainsKey('mob')) { return 0 }
            $label = [string]$mention.Semantics['mob'].Value
            if (-not $aliases.ContainsKey($label)) { return 0 }
            $units = $label.Split(' ')
            $mw = $mention.Words
            if ($mw.Count -le $units.Length) { return 0 }
            for ($i=0; $i -le $mw.Count-$units.Length; $i++) {
                $last = $i+$units.Length-1
                $valid = $true
                for ($j=0; $j -lt $units.Length; $j++) {
                    if ($mw[$i+$j].LexicalForm -ne $units[$j] -or $mw[$i+$j].Confidence -lt 0.60) { $valid=$false; break }
                }
                if (-not $valid) { continue }
                # Check the name's edges against an unconstrained interpretation of the same sound.
                # This prevents taking 'mob' from inside 'mobility' or 'spider' from 'spidery'.
                $span = $mention.GetAudioForWordRange($mw[$i],$mw[$last])
                $left = $span.AudioPosition.TotalMilliseconds
                $right = $left + $span.Duration.TotalMilliseconds
                $plainWords = $plain.Words
                $startEdge = $endEdge = $false
                for ($k=0; $k -lt $plainWords.Count; $k++) {
                    $word = $plain.GetAudioForWordRange($plainWords[$k],$plainWords[$k])
                    if ([Math]::Abs($word.AudioPosition.TotalMilliseconds-$left) -le 75) { $startEdge=$true }
                    if ([Math]::Abs(($word.AudioPosition+$word.Duration).TotalMilliseconds-$right) -le 75) { $endEdge=$true }
                }
                if (-not ($startEdge -and $endEdge)) { continue }
                $dictation = ' ' + (($plainWords | ForEach-Object { $_.LexicalForm.ToLowerInvariant() }) -join ' ') + ' '
                if ($dictation.Contains(' '+$label+' ')) { return $aliases[$label] }
                # A made-up name may be absent from dictation. Re-recognize its whole-word audio
                # with the short command (or pronunciation) grammar competing against ordinary speech.
                if ($confirm.ContainsKey($label)) { continue }
                $wordAudio = [IO.MemoryStream]::new()
                try {
                    $silence = [byte[]]::new(6400)
                    $wordAudio.Write($silence,0,$silence.Length)
                    $span.WriteToAudioStream($wordAudio)
                    $wordAudio.Write($silence,0,$silence.Length)
                    $wordAudio.Position=0
                    $engine.SetInputToNull()
                    $sentence.Enabled=$false
                    $calls.Enabled=$true
                    $verifyEngine = if ($null -ne $pronouncer -and $lexicon.Contains($label)) { $pronouncer } else { $engine }
                    try {
                        $verifyEngine.SetInputToAudioStream($wordAudio,$format)
                        $isolated = $verifyEngine.Recognize()
                        if ($null -ne $isolated -and $isolated.Text -eq $label -and $isolated.Confidence -ge 0.70) {
                            return $aliases[$label]
                        }
                    } finally { $verifyEngine.SetInputToNull() }
                } finally { $wordAudio.Dispose() }
            }
            return 0
        } finally {
            $engine.SetInputToNull()
            $sentence.Enabled=$false
            $calls.Enabled=$true
            $audio.Dispose()
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
    $output.WriteByte(82)
    $output.Flush()
    while ($true) {
        try { $length = $reader.ReadInt32() } catch [System.IO.EndOfStreamException] { break }
        if ($length -lt -256000) { exit 3 }
        $detail = $length -lt 0
        if ($detail) { $length = -$length }
        if ($length -le 0 -or $length -gt 256000 -or $length % 2 -ne 0) { exit 3 }
        $pcm = $reader.ReadBytes($length)
        if ($pcm.Length -ne $length) { exit 3 }
        $stream = [System.IO.MemoryStream]::new($pcm, $false)
        $guess = $null
        $decision = 0
        try {
            $engine.SetInputToAudioStream($stream, $format)
            $result = $engine.Recognize()
            $plain = if ($null -ne $result -and $result.Grammar -eq $background) { $result } else { $null }
            if ($detail -and $null -ne $result) { $guess = $result.Text }
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
                    if ($detail -and -not $guess -and $null -ne $result) { $guess = $result.Text }
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
                    if ($detail -and -not $guess -and $null -ne $match) { $guess = $match.Text }
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
            if ($decision -eq 0 -and $null -ne $plain) { $decision = Find-Mention $plain $pcm }
        } finally {
            $engine.SetInputToNull()
            $stream.Dispose()
            [Array]::Clear($pcm, 0, $length)
            $result = $null
            $plain = $null
        }
        $output.WriteByte([byte]($decision -shr 8))
        $output.WriteByte([byte]($decision -band 255))
        if ($detail) {
            # Guesses are display-only. They never enter the command decision or leave this device.
            if ($decision -ne 0 -or -not $guess) {
                $output.WriteByte(0)
            } else {
                $guess = (($guess -replace '[^\p{L}\p{M}\p{Nd}'' -]',' ') -replace ' +',' ').Trim()
                if ($guess.Length -gt 32) { $guess = $guess.Substring(0,31).TrimEnd() + [char]0x2026 }
                $hint = [Text.Encoding]::UTF8.GetBytes($guess)
                $output.WriteByte([byte]$hint.Length)
                $output.Write($hint,0,$hint.Length)
                [Array]::Clear($hint,0,$hint.Length)
            }
            $guess = $null
        }
        $output.Flush()
    }
} catch {
    exit 1
} finally {
    if ($null -ne $engine) { $engine.Dispose() }
    if ($null -ne $pronouncer) { $pronouncer.Dispose() }
}

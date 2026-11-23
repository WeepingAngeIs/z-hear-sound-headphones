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

    $modelDir = $env:MOBSEAR_LOCAL_WORD_MODEL
    $nearNames = @()
    $nearPokemon = @()
    $nearMatcher = $null
    if ($env:MOBSEAR_NEARBY_MATCHER) { Add-Type -TypeDefinition $env:MOBSEAR_NEARBY_MATCHER }
    # Written equivalents of the short-call pronunciation variants in the English lexicon.
    $spokenForms = @{'la punny'='lopunny'; 'lapunny'='lopunny'; 'lop bunny'='lopunny'}
    $ordinaryRivals = @{
        lopunny='\b(low( on)?|little|more) money\b'
        lanturn='\b(lanterns?|land turn)\b'
    }
    $compounds = @{}
    foreach ($label in $aliases.Keys) {
        $tokens = $label.Split(' ')
        for ($i=1; $i -lt $tokens.Count; $i++) {
            foreach ($part in @(($tokens[0..($i-1)] -join ' '), ($tokens[$i..($tokens.Count-1)] -join ' '))) {
                if ($aliases.ContainsKey($part) -and $aliases[$part] -ne $aliases[$label]) { $compounds[$part]=$true }
            }
        }
    }

    function Find-WholeName([string]$text, $plainWords, [int]$shortCode) {
        $normalized = $text.ToLowerInvariant().Normalize([Text.NormalizationForm]::FormD) -replace '\p{M}+',''
        $normalized = $normalized.Replace([string][char]0x2019,'').Replace('''','') -replace '\bmr\b','mister' -replace '\bjr\b','junior'
        $tokens = @([regex]::Matches($normalized, '[a-z]+') | ForEach-Object { $_.Value })
        if ($tokens.Count -eq 0 -or $tokens.Count -gt 80) { return 0 }
        $plainText=$plainWords -join ' '
        for ($i=0; $i -lt $tokens.Count; $i++) {
            for ($size=[Math]::Min(8,$tokens.Count-$i); $size -gt 0; $size--) {
                $label = $tokens[$i..($i+$size-1)] -join ' '
                if ($spokenForms.ContainsKey($label) -and $lexicon.Contains($spokenForms[$label])) {
                    $label = $spokenForms[$label]
                } elseif ($size -in @(2,3) -and -not $aliases.ContainsKey($label)) {
                    # Dictation can split a custom species into complete words, e.g. Pika Chu.
                    $joined = $label.Replace(' ','')
                    if ($joined.Length -ge 6 -and $nearNames -contains $joined) { $label=$joined }
                }
                if (-not $aliases.ContainsKey($label) -or $aliases[$label] -eq 0 -or
                        $wordBreaks.ContainsKey($label) -or $confirm.ContainsKey($label)) { continue }
                if ($ordinaryRivals.ContainsKey($label) -and $plainText -match $ordinaryRivals[$label]) { continue }
                # Leave bare labels and incomplete longer names to the established command recognizer.
                if (($tokens.Count -eq $size -and $aliases[$label] -ne $shortCode) -or ($compounds.ContainsKey($label) -and $tokens.Count -le $size+3 -and
                        -not ($tokens -match '^(is|was|were|see|saw|here|there|near|over|ran|went|came|found|watch|beside)$'))) { continue }
                if ($size -eq 1) {
                    $close = $false
                    foreach ($original in $plainWords) {
                        if ($original -eq $label -or $original.Length -lt 3) { continue }
                        if ($original.StartsWith($label) -and $original.Length -gt $label.Length) { $close=$true; break }
                        if ($original.Length -ne $label.Length) { continue }
                        $changes=0
                        for ($j=0; $j -lt $label.Length; $j++) { if ($original[$j] -ne $label[$j]) { $changes++ } }
                        # An independently recognized ordinary word such as lantern beats a prompted homophone.
                        if ($changes -le 1 -and -not $aliases.ContainsKey($original)) { $close=$true; break }
                    }
                    if ($close) { continue }
                }
                return $aliases[$label]
            }
        }
        return 0
    }

    function Read-WordAudio([byte[]]$samples, $original, [int]$shortCode, [int]$budget) {
        # Hints come from registered local creatures and an existing pronunciation lexicon. Dictation remains open.
        $hints = @(($nearPokemon + $nearNames + @($lexicon.Keys) + @('pikachu')) | Where-Object {
            $aliases.ContainsKey($_) -and $aliases[$_] -gt 0 -and -not $confirm.ContainsKey($_)
        } | Select-Object -Unique -First 24 | ForEach-Object { $english.Culture.TextInfo.ToTitleCase($_) })
        $prompt = (@('Minecraft','Cobblemon') + $hints) -join ', '
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = [IO.Path]::Combine($modelDir,'whisper-cli.exe')
        $start.WorkingDirectory = $modelDir
        # The pinned CLI disables its stdout segment callback for stdin unless an output basename
        # is set. No file-output format is enabled. The segment callback writes only into our pipe.
        $start.Arguments = '-m tiny.en.bin -f - -of pipe-only -t 3 -nt -np -l en -bs 5 -ac 512 --prompt '+[char]34+$prompt+'.'+[char]34
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardInput = $start.RedirectStandardOutput = $start.RedirectStandardError = $true
        $child = [Diagnostics.Process]::new()
        $child.StartInfo = $start
        try {
            if (-not $child.Start()) { return 65534 }
            $stdout = $child.StandardOutput.ReadToEndAsync()
            $stderr = $child.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
            # Only the WAV header and memory-resident capture are piped to the decoder.
            $pipe = [IO.BinaryWriter]::new($child.StandardInput.BaseStream)
            try {
                $pipe.Write([Text.Encoding]::ASCII.GetBytes('RIFF'))
                $pipe.Write([int](36+$samples.Length))
                $pipe.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
                $pipe.Write([int]16); $pipe.Write([int16]1); $pipe.Write([int16]1)
                $pipe.Write([int]16000); $pipe.Write([int]32000); $pipe.Write([int16]2); $pipe.Write([int16]16)
                $pipe.Write([Text.Encoding]::ASCII.GetBytes('data'))
                $pipe.Write([int]$samples.Length); $pipe.Write($samples)
            } finally { $pipe.Dispose() }
            if (-not $child.WaitForExit($budget)) { $child.Kill(); return 65535 }
            if ($child.ExitCode -ne 0) { return 65534 }
            $text = $stdout.GetAwaiter().GetResult()
            if ($text.Length -le 500) {
                $exact = Find-WholeName $text $original $shortCode
                if ($exact) { return $exact }
                if ($null -ne $nearMatcher) {
                    $plain = $original -join ' '
                    # Never redirect an exactly named, absent species to its local relative.
                    $tokens = @([regex]::Matches($text.ToLowerInvariant(),'[a-z]+') | ForEach-Object { $_.Value })
                    for ($i=0; $i -lt $tokens.Count; $i++) {
                        for ($n=[Math]::Min(4,$tokens.Count-$i); $n -gt 0; $n--) {
                            $word = $tokens[$i..($i+$n-1)] -join ' '
                            if ($aliases.ContainsKey($word) -and $nearPokemon -notcontains $word) { return 0 }
                        }
                    }
                    $selected = $nearMatcher.Pick($text,$plain)
                    if ($selected) { return (32768 -bor $aliases[$selected]) }
                }
            }
            return 0
        } catch {
            return 65534
        } finally {
            try { if (-not $child.HasExited) { $child.Kill() } } catch {}
            $child.Dispose()
        }
    }

    function Read-ConnectedSpeech([byte[]]$samples, $plain, $command) {
        if (-not $modelDir) { return 0 }
        $shortCode=0
        if ($null -ne $command -and $command.Grammar -eq $calls -and $command.Confidence -ge 0.80) {
            $name=$command.Text.ToLowerInvariant()
            if ($nearNames -contains $name -and $aliases.ContainsKey($name) -and -not $confirm.ContainsKey($name) -and
                    -not $compounds.ContainsKey($name) -and $name -notin @('hoglin','poponee')) { $shortCode=$aliases[$name] }
        }
        $hasContext=$null -ne $plain -and $plain.Words.Count -ge 2
        if (-not $hasContext -and $shortCode -eq 0 -and $null -eq $nearMatcher) { return 0 }
        $original=@()
        if ($null -ne $plain) { $original=@($plain.Words | ForEach-Object { $_.LexicalForm.ToLowerInvariant() }) }
        $answer=Read-WordAudio $samples $original $shortCode 3200
        if (-not $hasContext) {
            if ($answer -eq $shortCode -or $answer -ge 32768) { return $answer }
            return 0
        }
        return $answer
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
        if ($length -eq 0) {
            $count = $reader.ReadInt32()
            if ($count -lt 0 -or $count -gt 1048576) { exit 3 }
            $data = $reader.ReadBytes($count)
            if ($data.Length -ne $count) { exit 3 }
            $rows = [Text.Encoding]::ASCII.GetString($data).Split([char]10)
            $nearPokemon = @($rows | Where-Object { $_.StartsWith('+') } | ForEach-Object { $_.Substring(1) } |
                Where-Object { $aliases.ContainsKey($_) -and $aliases[$_] -gt 0 })
            $nearNames = @(($nearPokemon + @($rows | Where-Object { $aliases.ContainsKey($_) })) | Select-Object -Unique -First 24)
            $nearMatcher = if ($nearPokemon.Count -and ('NearbyPokemonWords' -as [type])) {
                [NearbyPokemonWords]::new([string[]]$nearPokemon)
            } else { $null }
            continue
        }
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
            if ($decision -eq 0) {
                try { $decision = Read-ConnectedSpeech $pcm $plain $result }
                catch { $decision = 65534 }
            }
        } finally {
            $engine.SetInputToNull()
            $stream.Dispose()
            $plain = $null
            [Array]::Clear($pcm, 0, $length)
            $result = $null
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
    if ($env:MOBSEAR_SYNTHETIC_WORKER_FAULTS -eq '1') {
        [Console]::Error.WriteLine(('SYNTH_WORKER_FAULT type={0} line={1}' -f $_.Exception.GetType().Name,$_.InvocationInfo.ScriptLineNumber))
    }
    exit 1
} finally {
    if ($null -ne $engine) { $engine.Dispose() }
    if ($null -ne $pronouncer) { $pronouncer.Dispose() }
}

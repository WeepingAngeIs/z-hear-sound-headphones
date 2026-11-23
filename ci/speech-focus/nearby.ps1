param([string]$Directory)
$ErrorActionPreference = 'Stop'
if ($env:RUNNER_ENVIRONMENT -ne 'github-hosted') { throw 'Hosted generated speech only' }
$env:MOBSEAR_NEARBY_MATCHER=Get-Content -Raw src/main/resources/dev/mobsear/client/NearbyPokemonWords.cs
Add-Type -TypeDefinition $env:MOBSEAR_NEARBY_MATCHER
foreach ($order in @('nidorina,nidorino','nidorino,nidorina')) {
    $names = $order.Split(',')
    $pick = [NearbyPokemonWords]::new($names)
    if ($pick.Pick('I saw a Nidorine tonight','') -ne $names[0]) { throw 'Equal pronunciation did not use distance order' }
    if ($pick.Pick('The Nidorino is beside me','') -ne 'nidorino') { throw 'Distance displaced a better name' }
    if ($pick.Pick('Nobody noticed the new notebook','')) { throw 'Initial alone selected a name' }
}
Write-Output 'NEARBY_RANK_PASS equal scores select the nearer individual in both orders'
if (-not [NearbyPokemonWords]::IsUnregisteredPause([string[]]@('hold','on'),@{}) -or
        [NearbyPokemonWords]::IsUnregisteredPause([string[]]@('hold','on'),@{'hold on'=99}) -or
        [NearbyPokemonWords]::IsUnregisteredPause([string[]]@('wait','a','second'),@{'second'=8}) -or
        [NearbyPokemonWords]::IsUnregisteredPause([string[]]@('hold','on','lopunny'),@{})) {
    throw 'Conversational pause handling must preserve names and custom aliases'
}
Add-Type -AssemblyName System.Speech
Add-Type -Path "$PSScriptRoot/SyntheticPronunciation.cs" -ReferencedAssemblies ([System.Speech.Synthesis.SpeechSynthesizer].Assembly.Location)
$synth=[System.Speech.Synthesis.SpeechSynthesizer]::new()
$format=[System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,16,1)
$lines=@{
    'exact'='The Pikachu ran past me.'
    'charmandor'='I saw a Charmandor by the river.'
    'bulbazore'='There is a Bulbazore here.'
    'nidorine'='That Nidorine is standing right here.'
    'bulbazar'='I saw a Bulbazar by the water.'
    'charmendar'='The Charmendar ran past me.'
    'ordinary'='Nobody noticed the new notebook.'
    'money'='We are low on money.'
    'lantern'='There is a lantern by the wall.'
    'notebook'='I need your notebook right here.'
    'needle'='I saw a needle in the sewing kit.'
    'pause'='Hold on.'
    'moment'='Just a moment.'
    'pause-name'='Hold on, the Pikachu ran past me.'
    'butterfree'='Butterfree.'
    'butterfree-sentence'='Look, there is a Butterfree by the tree.'
    'lilligant'='Lilligant.'
    'lilligant-sentence'='The Lilligant is over here.'
    'geodude'='Geodude.'
    'geodude-sentence'='Hey, look at that Geodude.'
    'geodude-phonetic'='<phoneme alphabet="ipa" ph="&#x2C8;d&#x292;io&#x28A;dud">Geodude</phoneme>.'
    'geodude-phonetic-sentence'='Look at that <phoneme alphabet="ipa" ph="&#x2C8;d&#x292;io&#x28A;dud">Geodude</phoneme>.'
    'butterfly'='I saw a butterfly in the garden.'
    'elegant'='That is an elegant flower.'
    'geode'='I left a geode on the table.'
    'good-dude'='He is a good dude.'
}
try {
    foreach ($voice in $synth.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'en-US' }) {
        $synth.SelectVoice($voice.VoiceInfo.Name)
        foreach ($word in @('Geodude','geo dude','good dude')) {
            $phones=[SyntheticPronunciation]::Read($synth,$word,$false)
            $encoded=$phones.ToCharArray() | ForEach-Object { 'U+{0:X4}' -f [int]$_ }
            Write-Output ("GENERATED_PRONUNCIATION "+$voice.VoiceInfo.Name+" "+$word+" "+($encoded -join ' '))
        }
        foreach($key in $lines.Keys) {
            $mem=[IO.MemoryStream]::new()
            try {
                $synth.SetOutputToAudioStream($mem,$format)
                if ($lines[$key].Contains('<phoneme')) {
                    $synth.SpeakSsml('<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US">'+$lines[$key]+'</speak>')
                } else { $synth.Speak($lines[$key]) }
                $synth.SetOutputToNull()
                $pcm=[byte[]]::new($mem.Length+640*38)
                [Array]::Copy($mem.ToArray(),0,$pcm,640*10,$mem.Length)
                $tag=($voice.VoiceInfo.Name -replace '[^a-zA-Z]','')
                [IO.File]::WriteAllBytes((Join-Path $Directory ("near-"+$tag+"_"+$key+".pcm")),$pcm)
            } finally { $mem.Dispose() }
        }
    }
} finally { $synth.Dispose() }
python ci/speech-focus/nearby_cases.py $Directory
exit $LASTEXITCODE

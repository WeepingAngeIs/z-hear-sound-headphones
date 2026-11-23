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
Add-Type -AssemblyName System.Speech
$synth=[System.Speech.Synthesis.SpeechSynthesizer]::new()
$format=[System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,16,1)
$lines=@{
    'exact'='The Pikachu ran past me.'
    'charmandor'='I saw a Charmandor by the river.'
    'bulbazore'='There is a Bulbazore here.'
    'nidorine'='That Nidorine is standing right here.'
    'ordinary'='Nobody noticed the new notebook.'
    'money'='We are low on money.'
    'lantern'='There is a lantern by the wall.'
}
try {
    foreach ($voice in $synth.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'en-US' }) {
        $synth.SelectVoice($voice.VoiceInfo.Name)
        foreach($key in $lines.Keys) {
            $mem=[IO.MemoryStream]::new()
            try {
                $synth.SetOutputToAudioStream($mem,$format)
                $synth.Speak($lines[$key]); $synth.SetOutputToNull()
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

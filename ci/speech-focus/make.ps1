# Generate the exact same disposable PCM for the pinned and candidate Windows recognizers.
param([Parameter(Mandatory=$true)][string]$Directory)
$ErrorActionPreference='Stop'
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'Hosted synthetic fixtures only' }
Add-Type -AssemblyName System.Speech
$format=[System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(16000,16,1)
$tts=[System.Speech.Synthesis.SpeechSynthesizer]::new()
$voices=@($tts.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'en-US' } | Sort-Object { $_.VoiceInfo.Name } | Select-Object -First 3)
if ($voices.Count -lt 1) { throw 'Missing US English test voice' }
$words=@(
    @('enderman','Enderman','ENDERMAN'),@('call-enderman','call Enderman','ENDERMAN'),
    @('lopunny','Lopunny','cobblemon:lopunny'),@('call-lopunny','call Lopunny','cobblemon:lopunny'),
    @('lop-bunny','Lop Bunny','cobblemon:lopunny'),@('ender-man','Ender Man','ENDERMAN'),
    @('lop-schwa','<phoneme alphabet="ipa" ph="&#x2C8;l&#x251;p&#x259;ni">Lopunny</phoneme>','cobblemon:lopunny'),
    @('low-punny','<phoneme alphabet="ipa" ph="&#x2C8;lo&#x28A;p&#x28C;ni">Lopunny</phoneme>','cobblemon:lopunny'),
    @('ender-unstressed','<phoneme alphabet="ipa" ph="&#x2C8;&#x25B;nd&#x25A;m&#x259;n">Enderman</phoneme>','ENDERMAN'),
    @('ender-stressed','<phoneme alphabet="ipa" ph="&#x2C8;&#x25B;nd&#x25A;m&#xE6;n">Enderman</phoneme>','ENDERMAN'),
    @('buneary','Buneary','cobblemon:buneary'),@('endermite','Endermite','ENDERMITE'),
    @('poponee','Poponee','cobblemon:poponee'),@('hoglin','Hoglin','HOGLIN'),@('zoglin','Zoglin','ZOGLIN'),
    @('hold-on','hold on','neutral'),@('hanging','I was hanging the lantern','neutral'),
    @('jogging','jogging','neutral'),@('logged-in','I just logged in','neutral'),
    @('huddling','we were huddling together','neutral'),@('ordinary-chat','I was talking to my friend','neutral'),
    @('logging','logging','neutral'),@('hobbling','hobbling','neutral'),@('hopping','hopping','neutral'),
    @('hold-it','hold it for me','neutral'),@('hauling','hauling it back','neutral'),
    @('talking','we were talking outside','neutral'),@('time','how long will it take','neutral'),
    @('on-my-way','hang on I am coming','neutral'),
    @('bunny','bunny','neutral'),@('slender','slender man','neutral'),@('punny','that was funny','neutral'),
    @('menu','enter the menu','neutral'),@('ordinary','the lantern is on the table','neutral'),
    @('honey','more honey','neutral'),@('ending','in the morning','neutral'),
    @('but-any','but any','neutral'),@('anyone-can','but anyone can join','neutral'),
    @('interment','interment','neutral'),@('low-money','low money','neutral'),
    @('sentence-lopunny-end',"Hey, look, there's a Lopunny.",'cobblemon:lopunny'),
    @('sentence-lopunny-middle','That Lopunny is over here.','cobblemon:lopunny'),
    @('sentence-pikachu-middle','The Pikachu ran past me.','cobblemon:pikachu'),
    @('sentence-enderman-end','I can see an Enderman.','ENDERMAN'),
    @('sentence-spider-first','Spider tracks lead down here.','SPIDER'),
    @('sentence-compound','The cave spider is near the wall.','SPIDER'),
    @('sentence-long','After we crossed the river and followed the path, I saw a Pikachu by the old stone bridge.','cobblemon:pikachu'),
    @('sentence-partial-mob','Our mobility is improving.','neutral'),
    @('sentence-partial-spider','That was spidery handwriting.','neutral'),
    @('sentence-partial-ender','The bartender mentioned it.','neutral'),
    @('sentence-ordinary',"Hey, look, there's a lantern.",'neutral'))
$rows=@()
try {
    $voiceIndex=0
    foreach ($voice in $voices) {
        $tts.SelectVoice($voice.VoiceInfo.Name)
        foreach ($entry in $words) {
            foreach ($speed in @(0,2)) {
                # The second speaking rate exercises the two unprefixed names only.
                if ($speed -eq 2 -and $entry[0] -notin @('enderman','lopunny')) { continue }
                $tts.Rate=$speed
                $stream=[IO.MemoryStream]::new()
                try {
                    $tts.SetOutputToAudioStream($stream,$format)
                    if ($entry[1].StartsWith('<phoneme')) {
                        $tts.SpeakSsml('<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US">' + $entry[1] + '</speak>')
                    } else { $tts.Speak($entry[1]) }
                    $tts.SetOutputToNull()
                    $file="voice-$voiceIndex-$($entry[0])-$speed"
                    [IO.File]::WriteAllBytes((Join-Path $Directory ($file + '.raw')), $stream.ToArray())
                    $rows+=($file,$voice.VoiceInfo.Name,$entry[0],$speed,$entry[2] -join "`t")
                } finally { $stream.Dispose() }
            }
        }
        $voiceIndex++
    }
    [IO.File]::WriteAllLines((Join-Path $Directory 'sources.tsv'),[string[]]$rows)
} finally { $tts.Dispose() }

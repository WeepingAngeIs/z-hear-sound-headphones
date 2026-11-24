param([ValidateSet('prepare','commands','sentences','nearby','lifecycle','focused')][string]$Check)
$ErrorActionPreference = 'Stop'
if ($env:RUNNER_ENVIRONMENT -ne 'github-hosted') { throw 'Hosted synthetic inputs only' }
$jar = (Get-ChildItem dist -Filter '*.jar' | Where-Object { $_.Name -like 'Mic Voice Chat*' -or $_.Name -like 'mobsear-*' }).FullName
$libs = @('jna','jna-platform') | ForEach-Object { Join-Path (Resolve-Path 'dist') "$_-5.14.0.jar" }
$cp = (@((Join-Path (Get-Location) 'ci-classes'),$jar) + $libs) -join ';'
$env:MOBSEAR_FIXTURE_CP = $cp
$env:MOBSEAR_SPEECH_CATALOG = 'build/speech-species.tsv'
$env:MOBSEAR_SYNTHETIC_WORKER_FAULTS = '1'
$fixture = Join-Path $env:RUNNER_TEMP 'portable-speech-fixtures'
$env:MOBSEAR_SYNTH_TRACE_DIR = $fixture
function MustPass { if ($LASTEXITCODE -ne 0) { throw "Hosted tool exited with code $LASTEXITCODE" } }
if ($Check -eq 'prepare') {
    python ci/audit_archive.py $jar --version 0.3.14
    MustPass
    # The game's real 1.21.1 launcher supplies these libraries.
    $hash = @{'jna'='67bf3eaea4f0718cb376a181a629e5f88fa1c9dd';'jna-platform'='28934d48aed814f11e4c584da55c49fa7032b31b'}
    foreach($name in $hash.Keys) {
        $file = "dist/$name-5.14.0.jar"
        Invoke-WebRequest "https://libraries.minecraft.net/net/java/dev/jna/$name/5.14.0/$name-5.14.0.jar" -OutFile $file
        if ((Get-FileHash $file -Algorithm SHA1).Hash -ne $hash[$name]) { throw 'Unexpected launcher dependency' }
    }
    New-Item -ItemType Directory -Path ci-classes,$fixture -Force | Out-Null
    javac --release 21 -cp $cp -d ci-classes src/test/java/dev/mobsear/client/SpeechCuesFixture.java ci/speech-focus/FocusFixture.java ci/speech-focus/current/FixtureCapture.java ci/speech-focus/NearbySpeechFixture.java ci/speech-focus/NativeTrace.java ci/speech-focus/FailureReadings.java ci/speech-lifecycle/WorkerRecoveryFixture.java
    MustPass
    python ci/cobblemon/stage.py build/speech-mods
    MustPass
    python ci/cobblemon/speech_names.py build/speech-mods build/speech-species.tsv
    MustPass
    python ci/cobblemon/public_species.py --vocabulary build/speech-species.tsv
    MustPass
    ./ci/speech-focus/make.ps1 $fixture
    python ci/speech-focus/corpus.py $fixture
    MustPass
} elseif ($Check -eq 'commands') {
    java -cp $cp dev.mobsear.client.speech.NativeTrace $fixture
    MustPass
    java -cp $cp dev.mobsear.client.SpeechCuesFixture --windows-speech
    $commandResult=$LASTEXITCODE
    if($commandResult) { java -cp $cp dev.mobsear.client.speech.FailureReadings $fixture catalog; exit $commandResult }

} elseif ($Check -eq 'sentences') {
    java -cp $cp dev.mobsear.client.FocusFixture $fixture mentions
    MustPass
    python ci/speech-focus/mentions.py $fixture
    $mentionResult=$LASTEXITCODE
    if($mentionResult) { java -cp $cp dev.mobsear.client.speech.FailureReadings $fixture mentions; exit $mentionResult }

} elseif ($Check -eq 'lifecycle') {
    $env:MOBSEAR_WORKER_FIXTURE = Join-Path $env:RUNNER_TEMP 'speech-timeout-fixture'
    New-Item -ItemType Directory -Path $env:MOBSEAR_WORKER_FIXTURE -Force | Out-Null
    java -cp $cp dev.mobsear.client.WorkerRecoveryFixture
    MustPass
} elseif ($Check -eq 'focused') {
    $classes=Join-Path $fixture 'baseline-classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    javac --release 21 --source-path speech-baseline/src/main/java -d $classes ci/speech-focus/FocusFixture.java ci/speech-focus/baseline/FixtureCapture.java
    MustPass
    java -cp "$classes;speech-baseline/src/main/resources" dev.mobsear.client.FocusFixture $fixture baseline
    MustPass
    java -cp $cp dev.mobsear.client.FocusFixture $fixture candidate
    MustPass
    python ci/speech-focus/compare.py $fixture
    MustPass
} else {
    ./ci/speech-focus/nearby.ps1 $fixture
    MustPass
}

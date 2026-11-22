$ErrorActionPreference='Stop'
if ($env:RUNNER_ENVIRONMENT -ne 'github-hosted') { exit 3 }
$folder=$env:MOBSEAR_WORKER_FIXTURE
$inputPipe=[IO.BinaryReader]::new([Console]::OpenStandardInput())
$outPipe=[Console]::OpenStandardOutput()
$startup=[Console]::OpenStandardError()
function Step-Worker([byte]$step) { $startup.WriteByte($step); $startup.Flush() }
Step-Worker 81
$length=$inputPipe.ReadInt32()
$null=$inputPipe.ReadBytes($length)
Step-Worker 71
Step-Worker 69
Step-Worker 76
$launched=Join-Path $folder 'launched'
if ([IO.File]::Exists($launched)) { [IO.File]::WriteAllText((Join-Path $folder 'recovered'),'1') }
else { [IO.File]::WriteAllText($launched,'1') }
$outPipe.WriteByte(82)
$outPipe.Flush()
while ($true) {
    try { $length=$inputPipe.ReadInt32() } catch [IO.EndOfStreamException] { break }
    $null=$inputPipe.ReadBytes($length)
    $late=Join-Path $folder 'late-returned'
    $stall=Join-Path $folder 'stall'
    if (-not [IO.File]::Exists($late)) {
        Start-Sleep -Seconds 9
        [IO.File]::WriteAllText($late,'1')
    } elseif ([IO.File]::Exists($stall)) {
        [IO.File]::Delete($stall)
        Start-Sleep -Seconds 75
    }
    $outPipe.WriteByte(0)
    $outPipe.WriteByte(3)
    $outPipe.Flush()
}

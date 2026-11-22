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
$tests=[IO.File]::Exists((Join-Path $folder 'test-feedback'))
$number=0
while ($true) {
    try { $length=$inputPipe.ReadInt32() } catch [IO.EndOfStreamException] { break }
    $detail=$length -lt 0
    if ($detail) { $length=-$length }
    $null=$inputPipe.ReadBytes($length)
    if ($tests) {
        $number++
        [IO.File]::WriteAllText((Join-Path $folder ('request-' + $number)),$detail.ToString())
        Start-Sleep -Milliseconds 600
        $code=if ($number -eq 1) {3} elseif ($number -eq 2) {5} else {0}
        $outPipe.WriteByte(0)
        $outPipe.WriteByte($code)
        if ($detail) {
            $example=if ($number -in @(3,5)) {'low bunny'} else {''}
            $bytes=[Text.Encoding]::UTF8.GetBytes($example)
            $outPipe.WriteByte($bytes.Length)
            $outPipe.Write($bytes,0,$bytes.Length)
        }
        $outPipe.Flush()
        continue
    }
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

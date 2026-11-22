"""Generate an instrumented, hosted-only worker for the synthetic corpus. Never shipped."""
import os
import sys
from pathlib import Path

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise RuntimeError("Hosted synthetic fixtures only")
source = Path(sys.argv[1]).read_text()

def insert_before(anchor, extra):
    global source
    if source.count(anchor) != 1:
        raise ValueError(anchor)
    source = source.replace(anchor, extra + "\n" + anchor)

insert_before("            # The ordinary command grammar", """
            [Console]::Error.WriteLine(('PROBE_SETUP_ERROR line={0} error={1}' -f $_.InvocationInfo.ScriptLineNumber,$_.Exception.GetBaseException().Message))
""")
insert_before("    $output.WriteByte(82)", """
    [Console]::Error.WriteLine(('PROBE_SETUP engine={0}' -f ($null -ne $pronouncer)))
    function Show-Probe([string]$phase, $hypothesis) {
        if ($null -eq $hypothesis) { [Console]::Error.WriteLine("PROBE $phase no-match"); return }
        $kind = if ($hypothesis.Grammar -is [System.Speech.Recognition.DictationGrammar]) { 'dictation' } else { 'command' }
        $confidence = [math]::Round($hypothesis.Confidence, 3)
        [Console]::Error.WriteLine(('PROBE {0} {1} {2} [{3}]' -f $phase,$kind,$confidence,$hypothesis.Text))
    }
""")
insert_before("            $minConfidence = 0.58", "            Show-Probe initial $result")
insert_before("            # Check only rejections", "            Show-Probe primary $result")
insert_before("                    $confident = ", """
                    Show-Probe auxiliary $match
                    $probeDictation = $pronouncer.Grammars | Where-Object { $_ -is [System.Speech.Recognition.DictationGrammar] }
                    $pronouncer.SetInputToNull()
                    $probeDictation.Enabled = $false
                    try {
                        $probeStream = [System.IO.MemoryStream]::new($pcm, $false)
                        $pronouncer.SetInputToAudioStream($probeStream, $format)
                        Show-Probe constrained ($pronouncer.Recognize())
                    } finally {
                        $pronouncer.SetInputToNull()
                        $probeStream.Dispose()
                        $probeDictation.Enabled = $true
                    }
""")
Path(sys.argv[2], "probe.ps1").write_text(source, encoding="utf-8-sig")

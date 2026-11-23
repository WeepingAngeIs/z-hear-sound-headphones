using System;
using System.Collections.Generic;
using System.Speech.Synthesis;

// Inspect only stock synthesized fixtures. This class is excluded from the mod.
public static class SyntheticPronunciation {
    public static string Read(SpeechSynthesizer voice, string text, bool ssml) {
        var phones = new List<string>();
        EventHandler<PhonemeReachedEventArgs> record = (sender, e) => phones.Add(e.Phoneme);
        voice.SetOutputToNull();
        voice.PhonemeReached += record;
        try {
            if (ssml) voice.SpeakSsml(text);
            else voice.Speak(text);
        } finally {
            voice.PhonemeReached -= record;
        }
        return String.Join(" ", phones);
    }
}

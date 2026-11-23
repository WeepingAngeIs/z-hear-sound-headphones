using System;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;

// In-memory helper for the local English recognizer. Candidates arrive nearest species first.
public sealed class NearbyPokemonWords
{
    private readonly string[] names;
    private readonly string[] spellings;
    private readonly string[] sounds;
    private static readonly Regex Words = new Regex("[a-z]+", RegexOptions.Compiled);
    private static readonly Regex Repeated = new Regex("(.)\\1+", RegexOptions.Compiled);
    private static readonly HashSet<string> Pauses = new HashSet<string> {
        "hold on", "hang on", "one moment", "one second",
        "just a moment", "just a second", "wait a moment", "wait a second"
    };
    private static readonly HashSet<string> Ordinary = new HashSet<string>(
        ("a an the this that these those there theres is are was were and or but if for of to from with at by in on " +
         "he she it we you they my your his her our their hey look see saw found over here near after before around " +
         "can cant could would should will just ran runs went walked walking said say talking called someone " +
         "something nothing getting looking friend bunny funny honey money lantern lanterns chair chairs " +
         "jogging logging talking morning table bridge path fence river outside another anyone holding " +
         "well really like some next have has had meet met path grass house talking better butterfly butterflies").Split(' '));

    public NearbyPokemonWords(string[] candidates)
    {
        names = candidates;
        spellings = new string[names.Length];
        sounds = new string[names.Length];
        for (int i = 0; i < names.Length; i++) {
            spellings[i] = names[i].Replace(" ", "");
            sounds[i] = Sound(spellings[i]);
        }
    }

    private static string Sound(string word)
    {
        word = word.Replace("ph","f").Replace("ght","t").Replace("ck","k").Replace("qu","kw")
                   .Replace("sh","x").Replace("ch","j").Replace("th","t").Replace("dg","j");
        var key = new StringBuilder(word.Length);
        for (int i = 0; i < word.Length; i++) {
            char c = word[i];
            if (c == 'c') c = i+1 < word.Length && "eiy".IndexOf(word[i+1]) >= 0 ? 's' : 'k';
            if ("aeiouy".IndexOf(c) >= 0) c = 'a';
            key.Append(c);
        }
        return Repeated.Replace(key.ToString(), "$1");
    }

    // Optimal-string-alignment edit distance also accepts one transposed pair.
    private static int Edits(string a, string b)
    {
        int[] previous = new int[b.Length+1], row = new int[b.Length+1], older = new int[b.Length+1];
        for (int j = 0; j <= b.Length; j++) previous[j] = j;
        for (int i = 1; i <= a.Length; i++) {
            row[0] = i;
            for (int j = 1; j <= b.Length; j++) {
                row[j] = Math.Min(Math.Min(row[j-1]+1, previous[j]+1), previous[j-1]+(a[i-1] == b[j-1] ? 0 : 1));
                if (i > 1 && j > 1 && a[i-1] == b[j-2] && a[i-2] == b[j-1])
                    row[j] = Math.Min(row[j], older[j-2]+1);
            }
            var swap = older; older = previous; previous = row; row = swap;
        }
        return previous[b.Length];
    }

    private static double Similarity(string a, string b)
    {
        return 1.0 - (double)Edits(a,b)/Math.Max(a.Length,b.Length);
    }

    // After the command recognizers reject a clip, a complete conversational pause
    // needs no second audio decode. Registered names and player aliases take precedence.
    public static bool IsUnregisteredPause(string[] words, System.Collections.IDictionary registry)
    {
        if (words.Length < 2 || words.Length > 3 || !Pauses.Contains(String.Join(" ",words))) return false;
        for (int first = 0; first < words.Length; first++) {
            string span = "";
            for (int last = first; last < words.Length; last++) {
                span = last == first ? words[first] : span + " " + words[last];
                if (registry.Contains(span) || registry.Contains(span.Replace(" ",""))) return false;
            }
        }
        return true;
    }

    // Only spelling context for the audio decoder. A starting sound never selects a Pokémon.
    public static string[] MatchingStarts(string independent, string[] similar)
    {
        if (similar.Length < 2 || String.IsNullOrWhiteSpace(independent)) return new string[0];
        var starts = new HashSet<string>();
        foreach (Match word in Words.Matches(independent.ToLowerInvariant())) {
            if (Ordinary.Contains(word.Value) || word.Length < 3) continue;
            string sound = Sound(word.Value);
            if (sound.Length >= 3) starts.Add(sound.Substring(0,3));
        }
        var matches = new List<string>();
        foreach (string name in similar) {
            string sound = Sound(name.Replace(" ",""));
            if (sound.Length >= 3 && starts.Contains(sound.Substring(0,3))) matches.Add(name);
        }
        return matches.ToArray();
    }

    public string Pick(string decoded, string independent)
    {
        if (names.Length == 0 || String.IsNullOrWhiteSpace(decoded) || decoded.Length > 500) return null;
        var tokens = Words.Matches(decoded.ToLowerInvariant().Replace("'","").Replace("’",""));
        if (tokens.Count > 80) return null;
        var candidates = new HashSet<string>();
        for (int i = 0; i < tokens.Count; i++) {
            string span = "";
            for (int j = i; j < tokens.Count && j < i+3; j++) {
                string token = tokens[j].Value;
                if (Ordinary.Contains(token)) break;
                span += token;
                if (span.Length >= 3 && span.Length <= 30) candidates.Add(span);
            }
        }
        var reference = " " + (independent ?? "").ToLowerInvariant() + " ";
        var observed = new List<KeyValuePair<string,string>>();
        foreach (string span in candidates) observed.Add(new KeyValuePair<string,string>(span,Sound(span)));
        double best = 0;
        string choice = null;
        for (int i = 0; i < names.Length; i++) {
            var name = spellings[i];
            if (name.Length < 3 || name.Length > 30) continue;
            if ((name == "lopunny" && Regex.IsMatch(reference, @"\b(low( on)?|little|more) money\b")) ||
                (name == "lanturn" && Regex.IsMatch(reference, @"\blanterns?\b"))) continue;
            foreach (var word in observed) {
                string text = word.Key, sound = word.Value;
                int length = Math.Max(name.Length,text.Length);
                if (Math.Abs(name.Length-text.Length) > length/3) continue;
                double letters = Similarity(text,name);
                if (letters < 0.62) continue;
                double phonetic = Similarity(sound,sounds[i]);
                if (phonetic < 0.63) continue;
                // A matching initial contributes only two points. Short names need stronger evidence.
                double score = 0.48*letters + 0.50*phonetic + (sound[0] == sounds[i][0] ? 0.02 : 0);
                // A longer distinctive shared stem is stronger evidence than an initial.
                bool sharedStem = name.Length >= 8 && text.Length >= 8 &&
                                  String.CompareOrdinal(name,0,text,0,5) == 0;
                double minimum = length <= 4 ? 0.86 : sharedStem ? 0.76 : 0.785;
                if (score >= minimum && score > best + 0.000001) {
                    best = score;
                    choice = names[i];
                }
            }
        }
        return choice;
    }
}

package dev.mobsear.client.speech;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** The full registry owns exact names. Only the distance-ordered live roster owns approximate readings. */
final class CallVocabulary {
    private static final Pattern WORD = Pattern.compile("[a-z]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    static final int NEAR = 0x8000;
    final Map<String, Integer> names = new LinkedHashMap<>();
    final Set<String> confirm = new HashSet<>(), explicit = new HashSet<>();
    final Map<String, String> spoken = new LinkedHashMap<>();
    private static final Set<String> ORDINARY = new HashSet<>(Arrays.asList(("a an the this that these those there theres is are was were and or but if for of to from with at by in on " +
            "he she it we you they my your his her our their hey look see saw found over here near after before around " +
            "can cant could would should will just ran runs went walked walking said say talking called someone " +
            "something nothing getting looking friend bunny funny honey money lantern lanterns chair chairs " +
            "jogging logging talking morning table bridge path fence river outside another anyone holding " +
            "well really like some next have has had meet met grass house better good butterfly butterflies").split(" ")));
    private final Set<String> compoundParts = new HashSet<>();
    private List<String> nearby = List.of();
    private final Map<String, String> readings = new LinkedHashMap<>();

    CallVocabulary(byte[] bytes) throws IOException {
        for (String row : new String(bytes, StandardCharsets.US_ASCII).split("\n")) {
            String[] parts = row.split(":", 3);
            if (parts.length != 3 || !parts[2].matches("[a-z]+(?: [a-z]+)*")) throw new IOException("Invalid registry label");
            int code = Integer.parseInt(parts[0]);
            if (code < 0 || code >= NEAR || names.put(parts[2], code) != null) throw new IOException("Invalid registry code");
            if (parts[1].equals("1")) confirm.add(parts[2]);
            if (parts[1].equals("2")) explicit.add(parts[2]);
        }
        for (var entry : names.entrySet()) {
            String[] pieces=entry.getKey().split(" ");
            for(int i=1;i<pieces.length;i++) for(String part:List.of(
                    String.join(" ",Arrays.copyOfRange(pieces,0,i)),String.join(" ",Arrays.copyOfRange(pieces,i,pieces.length)))) {
                if(names.containsKey(part) && !names.get(part).equals(entry.getValue())) compoundParts.add(part);
            }
        }
        for (String bird : List.of("watt rel","wat rel","watt rul","watt roll","wat roll","watt trel","wah trel","watchroll","watch roll","watch and roll")) {
            alias(bird, "wattrel");
            for (String prefix : List.of("kilo ", "killa ", "kill a ")) alias(prefix+bird,"kilowattrel");
        }
        for (String prefix : List.of("kilo ", "killa ", "kill a ")) alias(prefix+"wattrel","kilowattrel");
        for (String word : List.of("vivian","vivien","vivienne","viv ee yon","vivi yon","vivvy on","viv ill on","viv il on","vivillion")) alias(word,"vivillon");
    }

    private void alias(String form, String target) {
        if (names.containsKey(target) && !names.containsKey(form)) spoken.put(form,target);
    }

    void nearby(List<String> ids) {
        nearby = List.copyOf(new LinkedHashSet<>(ids));
        readings.clear();
        for (String name : nearby) {
            for (String reading : spellings(name)) if (!names.containsKey(reading)) readings.putIfAbsent(reading,name);
        }
    }

    Map<String,String> localReadings() { return Collections.unmodifiableMap(readings); }
    boolean live(String label) { return nearby.contains(label); }

    static List<String> words(String text) {
        if (text.length()>500) return List.of();
        String normalized = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT).replace("'","").replace("’","").replaceAll("\\bmr\\b","mister").replaceAll("\\bjr\\b","junior");
        return WORD.matcher(normalized).results().limit(81).map(r -> r.group()).toList();
    }

    record Match(String label, int code) {}

    Match find(String text, boolean allowExplicit) {
        List<String> tokens = words(text);
        if (tokens.size()>80) return null;
        for (int i=0;i<tokens.size();i++) {
            for (int j=Math.min(tokens.size(),i+8);j>i;j--) {
                String phrase = String.join(" ",tokens.subList(i,j));
                if (names.containsKey(phrase) && (!explicit.contains(phrase) || allowExplicit))
                    return new Match(phrase,names.get(phrase));
                String alt = spoken.get(phrase);
                if (alt != null) return new Match(alt,live(alt) ? NEAR|names.get(alt) : 0);
                if (j-i>=2 && j-i<=3) {
                    String joined=phrase.replace(" ","");
                    if (joined.length()>=6 && live(joined) && names.containsKey(joined))
                        return new Match(joined,names.get(joined));
                }
            }
        }
        return null;
    }

    static boolean rival(String target,String reference) {
        String s = " "+String.join(" ",words(reference))+" ";
        return switch(target) {
            case "lopunny" -> s.matches(".*\\b(low( on)?|little|more) money\\b.*");
            case "lanturn" -> s.matches(".*\\b(lanterns?|land turn)\\b.*");
            default -> false;
        };
    }

    boolean compoundPart(String label) { return compoundParts.contains(label); }

    boolean partialCompound(String label,String text) {
        if(!compoundParts.contains(label)) return false;
        String plain=String.join(" ",words(text)).replaceFirst("^(?:hey|call) ","");
        if(plain.equals(label)) return false;
        int count=words(plain).size();
        return count>0 && count<=words(label).size()+3 && !plain.matches(".*\\b(is|was|were|see|saw|here|there|near|over|ran|went|came|found|watch|beside)\\b.*");
    }

    boolean unregisteredInflection(String text) {
        for(String word:words(text)) {
            if(names.containsKey(word)) continue;
            for(String suffix:List.of("y","ish","like","ness","ing")) {
                if(word.endsWith(suffix) && word.length()-suffix.length()>=4 &&
                        names.containsKey(word.substring(0,word.length()-suffix.length()))) return true;
            }
        }
        return false;
    }

    Match spokenRecovery(String text,String independent) {
        if(live("lopunny") && !rival("lopunny",independent)) {
            String heard=" "+String.join(" ",words(text))+" ";
            if(heard.matches(".*\\b(la[ph]?on[ein]+|lopon[ein]+|la punny|lop bunny|la ponee)\\b.*"))
                return new Match("lopunny",NEAR|names.get("lopunny"));
        }
        return null;
    }

    boolean conflicts(String label,String independentlyHeard) {
        if (rival(label,independentlyHeard)) return true;
        if (label.contains(" ")) return false;
        for (String word:words(independentlyHeard)) {
            if (word.equals(label) || word.length()<3 || Objects.equals(names.get(word), names.get(label))) continue;
            if (word.startsWith(label) && word.length()>label.length()) return true;
            if (word.length()==label.length() && !names.containsKey(word)) {
                int different=0;
                for(int i=0;i<word.length();i++) if(label.charAt(i)!=word.charAt(i)) different++;
                if(different==1) return true;
            }
        }
        return false;
    }

    Match guess(String decoded,String independent) {
        List<String> tok=words(decoded);
        if (tok.size()>80) return null;
        Set<String> spans = new LinkedHashSet<>();
        for (int i=0;i<tok.size();i++) {
            String span="";
            for (int j=i;j<Math.min(tok.size(),i+3) && !ORDINARY.contains(tok.get(j));j++) {
                span+=tok.get(j);
                if (span.length()>=3 && span.length()<=30) spans.add(span);
            }
        }
        double best=0;
        Match choice=null;
        for (String label:nearby) {
            if (rival(label,independent)) continue;
            String name=label.replace(" ",""), pronunciation=sound(name);
            if(name.length()<3 || name.length()>30) continue;
            for(String span:spans) {
                int len=Math.max(span.length(),name.length());
                if (Math.abs(span.length()-name.length())>len/3) continue;
                double letters=similarity(name,span), phonetic=similarity(pronunciation,sound(span));
                if(letters<0.62 || phonetic<0.63) continue;
                double score = label.equals(readings.get(span)) ? .98 : .48*letters+.50*phonetic+(pronunciation.charAt(0)==sound(span).charAt(0) ? .02 : 0);
                boolean stem=span.length()>=8 && name.length()>=8 && span.regionMatches(0,name,0,5);
                if (score>best+1e-6 && score>=(len<=4?.86:stem?.76:.785)) {
                    best=score; choice=new Match(label,NEAR|names.get(label));
                }
            }
        }
        return choice;
    }

    private static String sound(String word) {
        return word.replace("ph","f").replace("ght","t").replace("ck","k").replace("qu","kw")
                .replace("sh","x").replace("ch","j").replace("th","t").replace("dg","j")
                .replaceAll("c(?=[eiy])","s").replace('c','k').replaceAll("[aeiouy]","a").replaceAll("(.)\\1+","$1");
    }

    private static double similarity(String a,String b) {
        int[][] edits = new int[a.length()+1][b.length()+1];
        for(int i=0;i<=a.length();i++) edits[i][0]=i;
        for(int j=0;j<=b.length();j++) edits[0][j]=j;
        for(int i=1;i<=a.length();i++) for(int j=1;j<=b.length();j++) {
            int m=Math.min(Math.min(edits[i-1][j],edits[i][j-1])+1,edits[i-1][j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
            if(i>1 && j>1 && a.charAt(i-1)==b.charAt(j-2) && a.charAt(i-2)==b.charAt(j-1)) m=Math.min(m,edits[i-2][j-2]+1);
            edits[i][j]=m;
        }
        return 1-(double)edits[a.length()][b.length()]/Math.max(a.length(),b.length());
    }

    private static List<String> spellings(String name) {
        if(name.length()<5 || name.length()>30) return List.of();
        var forms = new ArrayList<String>();
        for(int i=0;i<name.length();i++) {
            String replacement = switch(name.charAt(i)) {
                case 'a' -> "ah"; case 'e' -> "eh"; case 'i' -> "ee"; case 'o' -> "oh"; case 'u' -> "oo";
                case 'c' -> i+1<name.length() && "eiy".indexOf(name.charAt(i+1))>=0 ? "s":"k"; default -> "";
            };
            if(!replacement.isEmpty()) forms.add(name.substring(0,i)+replacement+name.substring(i+1));
        }
        if(name.contains("ph")) forms.add(name.replace("ph","f"));
        if(name.endsWith("saur")) forms.add(name.substring(0,name.length()-4)+"zore");
        if(name.endsWith("chu")) forms.add(name.substring(0,name.length()-3)+"choo");
        return forms;
    }
}

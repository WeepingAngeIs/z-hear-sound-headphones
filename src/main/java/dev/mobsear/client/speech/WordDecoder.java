package dev.mobsear.client.speech;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** The same Tiny English acoustic weights as 0.3.13, evaluated by Windows ML instead of a shipped executable. */
final class WordDecoder implements AutoCloseable {
    private static final int FFT=400, BINS=201, FRAMES=1024, EOT=50256;
    private final WindowsModel model;
    private final ModelStartup.Progress startup;
    private final float[] filters=new float[80*BINS], cosine=new float[BINS*FFT], sine=new float[BINS*FFT];
    private final String[] vocab=new String[EOT+1];
    private final Map<String,Integer> tokenIds=new HashMap<>();
    private static final Pattern PIECES=Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+");
    private static final int[] SUPPRESS={1,2,7,8,9,10,14,25,26,27,28,29,31,58,59,60,61,62,63,90,91,92,93,357,366,438,532,685,705,796,930,1058,1220,1267,1279,1303,1343,1377,1391,1635,1782,1875,2162,2361,2488,3467,4008,4211,4600,4808,5299,5855,6329,7203,9609,9959,10563,10786,11420,11709,11907,13163,13697,13700,14808,15306,16410,16791,17992,19203,19510,20724,22305,22935,27007,30109,30420,33409,34949,40283,40493,40549,47282,49146};

    WordDecoder() throws IOException { this(new ModelStartup.Progress()); }

    WordDecoder(ModelStartup.Progress startup) throws IOException {
        this.startup = startup;
        try(var raw=resource("mel-filters.f32"); var tokens=new DataInputStream(new BufferedInputStream(resource("english.vocab")))) {
            var table=ByteBuffer.wrap(raw.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            if(table.remaining()!=filters.length) throw new IOException("Invalid mel filters");
            table.get(filters);
            for(int i=0;i<vocab.length;i++) {
                byte[] bytes=tokens.readNBytes(tokens.readUnsignedShort());
                vocab[i]=new String(bytes,StandardCharsets.ISO_8859_1);tokenIds.put(vocab[i],i);
            }
            if(tokens.read()!=-1) throw new IOException("Invalid English vocabulary");
        }
        for(int k=0;k<BINS;k++) for(int x=0;x<FFT;x++) {
            double window=.5*(1-Math.cos(2*Math.PI*x/FFT)), phase=2*Math.PI*k*x/FFT;
            cosine[k*FFT+x]=(float)(window*Math.cos(phase));
            sine[k*FFT+x]=(float)(window*Math.sin(phase));
        }
        startup.stage = ModelStartup.Stage.RUNTIME;
        model=new WindowsModel(startup);
    }

    private static InputStream resource(String name) throws IOException {
        var stream=WordDecoder.class.getResourceAsStream("model/"+name);
        if(stream==null) throw new IOException("Missing offline word data");
        return stream;
    }

    private float[] mel(byte[] pcm) {
        final int n=pcm.length/2;
        float[] result=new float[80*FRAMES];
        Arrays.fill(result,-10f);
        var window=new float[FFT];var power=new float[BINS];
        float maximum=-10;
        for(int t=0;t<Math.min(FRAMES,(n+FFT/2+159)/160);t++) {
            for(int x=0;x<FFT;x++) {
                int i=Math.abs(t*160+x-FFT/2);
                window[x]=i<n ? (short)((pcm[2*i]&255)|(pcm[2*i+1]<<8))/32768f : 0;
            }
            for(int k=0;k<BINS;k++) {
                float re=0,im=0;
                for(int x=0;x<FFT;x++){re+=window[x]*cosine[k*FFT+x];im+=window[x]*sine[k*FFT+x];}
                power[k]=re*re+im*im;
            }
            for(int m=0;m<80;m++) {
                float sum=0;for(int k=0;k<BINS;k++) sum+=power[k]*filters[m*BINS+k];
                float value=(float)Math.log10(Math.max(1e-10f,sum));
                result[m*FRAMES+t]=value;maximum=Math.max(maximum,value);
            }
        }
        for(int i=0;i<result.length;i++) result[i]=(Math.max(result[i],maximum-8)+4)/4;
        Arrays.fill(window,0);Arrays.fill(power,0);
        return result;
    }

    private List<Integer> tokenize(String prompt) {
        var ids=new ArrayList<Integer>();
        var matcher=PIECES.matcher(prompt);
        while(matcher.find()) {
            String part=new String(matcher.group().getBytes(StandardCharsets.UTF_8),StandardCharsets.ISO_8859_1);
            for(int i=0;i<part.length();) {
                int end=part.length();
                while(end>i+1 && !tokenIds.containsKey(part.substring(i,end))) end--;
                Integer id=tokenIds.get(part.substring(i,end));
                if(id!=null) ids.add(id);
                i=end;
            }
        }
        return ids;
    }

    void warm() {
        float[] quiet=new float[80*FRAMES];
        Arrays.fill(quiet,-1.5f);
        startup.stage = ModelStartup.Stage.ENCODER_WARMUP;
        try(var context=model.encode(quiet)) {
            startup.stage = ModelStartup.Stage.DECODER_WARMUP;
            context.logits(new long[]{50257,50362},1);
        }
    }

    String recognize(byte[] pcm,Collection<String> names) {
        var prompt=new StringBuilder(" Minecraft, Cobblemon");
        for(String name:names.stream().distinct().limit(24).toList()) {
            prompt.append(", ");
            for(String word:name.split(" ")) if(!word.isEmpty()) prompt.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
            prompt.setLength(prompt.length()-1);
        }
        var ids=new ArrayList<Integer>();ids.add(50360);
        var words=tokenize(prompt.append('.').toString());
        ids.addAll(words.subList(Math.max(0,words.size()-80),words.size()));
        ids.add(50257);ids.add(50362);
        int start=ids.size();
        long[] seed=ids.stream().mapToLong(Integer::longValue).toArray();
        int width=pcm.length<46080 ? 3 : 5;
        var active=List.of(new Beam(seed,0));
        Beam done=null;
        long deadline=System.nanoTime()+3_150_000_000L;
        float[] features=mel(pcm);
        try(var context=model.encode(features)) {
            for(int step=0;step<32 && !active.isEmpty();step++) {
                int length=active.getFirst().tokens.length;
                long[] batch=new long[active.size()*length];
                for(int b=0;b<active.size();b++) System.arraycopy(active.get(b).tokens,0,batch,b*length,length);
                float[] logits=context.logits(batch,active.size());
                var next=new ArrayList<Beam>();
                for(int b=0;b<active.size();b++) {
                    int offset=b*51864;
                    for(int id:SUPPRESS) logits[offset+id]=Float.NEGATIVE_INFINITY;
                    if(step==0) { logits[offset+EOT]=Float.NEGATIVE_INFINITY;logits[offset+220]=Float.NEGATIVE_INFINITY; }
                    int[] best=new int[width+1]; Arrays.fill(best,-1);
                    for(int k=0;k<=EOT;k++) {
                        float score=logits[offset+k];
                        if(best[width]>=0 && score<=logits[offset+best[width]]) continue;
                        int at=width;
                        while(at>0 && (best[at-1]<0 || score>logits[offset+best[at-1]])) {
                            best[at]=best[at-1];at--;
                        }
                        best[at]=k;
                    }
                    double max=logits[offset+best[0]],sum=0;
                    for(int k=0;k<=EOT;k++) sum+=Math.exp(logits[offset+k]-max);
                    double normal=max+Math.log(sum);
                    for(int id:best) {
                        var parent=active.get(b);
                        double score=parent.score+logits[offset+id]-normal;
                        if(id==EOT) {
                            if(step>0) {
                                var complete=new Beam(parent.tokens,score);
                                if(done==null || complete.normalized(start)>done.normalized(start)) done=complete;
                            }
                        } else {
                            long[] child=Arrays.copyOf(parent.tokens,length+1);child[length]=id;
                            next.add(new Beam(child,score));
                        }
                    }
                }
                Arrays.fill(logits,0);
                next.sort(Comparator.comparingDouble(Beam::score).reversed());
                active=next.subList(0,Math.min(width,next.size()));
                if(done!=null && (active.isEmpty() || done.normalized(start)>=active.getFirst().normalized(start))) break;
                if(System.nanoTime()>=deadline) break;
            }
            Beam chosen=done!=null ? done : active.isEmpty() ? null : active.getFirst();
            if(chosen==null) return "";
            StringBuilder output=new StringBuilder();
            for(int i=start;i<chosen.tokens.length;i++) output.append(vocab[(int)chosen.tokens[i]]);
            return new String(output.toString().getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8).strip();
        } finally { Arrays.fill(features,0); }
    }

    private record Beam(long[] tokens,double score) {
        double normalized(int prefix) { return score/Math.max(1,tokens.length-prefix); }
    }

    @Override public void close(){model.close();}
}

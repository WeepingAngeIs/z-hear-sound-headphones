package dev.mobsear.client.speech;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bind data-only ONNX graphs to the Windows 10/11 MachineLearning WinRT API on the local CPU. */
final class WindowsModel implements AutoCloseable {
    private static final NativeLibrary COM = NativeLibrary.getInstance(Path.of(System.getenv("SystemRoot"),"System32","combase.dll").toString());
    private static final String PREFIX="Windows.AI.MachineLearning.";
    private static final String MODEL="e3b977e8-6952-4e47-8ef4-1f7f07897c6d";
    private static final String SESSION="0f6b881d-1c9b-47b6-bfe0-f1cf62a67579";
    private static final String BINDING="c95f7a7a-e788-475e-8917-23aa381faf0b";
    private static final String FLOATS="24610bc1-5e44-5713-b281-8f4ad4d555e8";
    private static final String INTS="6d3d9dcb-ff40-5ec2-89fe-084e2b6bc6db";
    private static final String FLOAT="f2282d82-aa02-42c8-a0c8-df1efc9676e1";
    private Pointer floatFactory,intFactory,bindingFactory,encoder,decoder;
    private boolean initialized;

    WindowsModel() throws IOException {
        Sapi.ok(system("RoInitialize").invokeInt(new Object[]{1}),"RoInitialize");
        initialized=true;
        try {
            floatFactory=factory("TensorFloat",FLOATS);
            intFactory=factory("TensorInt64Bit",INTS);
            bindingFactory=factory("LearningModelBinding",BINDING);
            encoder=load("encoder"); decoder=load("decoder");
        } catch(IOException|RuntimeException|Error e) { close(); throw e; }
    }

    private static Function system(String name) { return COM.getFunction(name,Function.ALT_CONVENTION); }
    private static final class Text implements AutoCloseable {
        private final Pointer handle;
        Text(String value) {
            var out=new PointerByReference();
            Sapi.ok(system("WindowsCreateString").invokeInt(new Object[]{new WString(value),value.length(),out}),"create string");
            handle=out.getValue();
        }
        @Override public void close() { system("WindowsDeleteString").invokeInt(new Object[]{handle}); }
    }
    private static Pointer factory(String name,String iid) {
        var out=new PointerByReference();
        try(var cls=new Text(PREFIX+name);var id=Sapi.guid(iid)) {
            Sapi.ok(system("RoGetActivationFactory").invokeInt(new Object[]{cls.handle,id,out}),"Windows ML "+name);
            return out.getValue();
        }
    }

    private Pointer load(String name) throws IOException {
        // WinML consumes the data synchronously. Only model data is copied, never samples or program code.
        Path path=Files.createTempFile("mobsear-"+name+"-",".onnx");
        Pointer mf=null,sf=null,model=null;
        try {
            try(var input=Objects.requireNonNull(getClass().getResourceAsStream("model/"+name+".onnx"))) {
                Files.copy(input,path,StandardCopyOption.REPLACE_EXISTING);
            }
            mf=factory("LearningModel",MODEL);
            sf=factory("LearningModelSession",SESSION);
            var result=new PointerByReference();
            try(var filename=new Text(path.toAbsolutePath().toString())) {
                Sapi.ok(Sapi.call(mf,8,filename.handle,result),"load "+name);
            }
            model=result.getValue();
            Sapi.ok(Sapi.call(sf,6,model,result),"create "+name+" CPU session");
            return result.getValue();
        } finally {
            Sapi.release(model);Sapi.release(sf);Sapi.release(mf);
            try { Files.deleteIfExists(path); } catch(IOException busy) { path.toFile().deleteOnExit(); }
        }
    }

    private Pointer tensor(long[] shape,Object data,int count,Pointer factory) {
        var out=new PointerByReference();
        Sapi.ok(Sapi.call(factory,6,shape.length,shape,count,data,out),"create input tensor");
        return out.getValue();
    }
    private Pointer bind(Pointer session) {
        var out=new PointerByReference();
        Sapi.ok(Sapi.call(bindingFactory,6,session,out),"create binding");return out.getValue();
    }
    private void bind(Pointer binding,String name,Pointer tensor) {
        try(var key=new Text(name)) { Sapi.ok(Sapi.call(binding,6,key.handle,tensor),"bind "+name); }
    }
    private Pointer evaluate(Pointer session,Pointer binding) {
        var out=new PointerByReference();
        Sapi.ok(Sapi.call(session,11,binding,null,out),"evaluate speech data");
        Pointer result=out.getValue();
        try {
            var success=new ByteByReference();
            Sapi.ok(Sapi.call(result,8,success),"get model status");
            if(success.getValue()==0) {
                var error=new IntByReference();
                Sapi.ok(Sapi.call(result,7,error),"get model error");
                throw new IllegalStateException("Windows ML evaluate HRESULT "+Integer.toHexString(error.getValue()));
            }
            Sapi.ok(Sapi.call(result,9,out),"get outputs");
            return out.getValue();
        } finally { Sapi.release(result); }
    }
    private Pointer output(Pointer map,String name) {
        try(var key=new Text(name)) {
            var out=new PointerByReference();
            Sapi.ok(Sapi.call(map,6,key.handle,out),"get tensor "+name);
            return out.getValue();
        }
    }

    Context encode(float[] mel) {
        Pointer binding=bind(encoder), mapped=null, data=null;
        var result=new Context();
        try {
            data=tensor(new long[]{1,80,1024},mel,mel.length,floatFactory);bind(binding,"mel",data);
            mapped=evaluate(encoder,binding);
            for(int i=0;i<4;i++) for(String prefix:List.of("k","v")) {
                String key=prefix+i;result.tensors.put(key,output(mapped,key));
            }
            return result;
        } catch(RuntimeException|Error e) { result.close();throw e; }
        finally { Sapi.release(mapped);Sapi.release(data);Sapi.release(binding); }
    }

    final class Context implements AutoCloseable {
        final Map<String,Pointer> tensors=new LinkedHashMap<>();
        float[] logits(long[] tokens,int beams) {
            int n=tokens.length/beams;
            var positions=new long[n];var mask=new float[n*n];
            for(int i=0;i<n;i++) { positions[i]=i; Arrays.fill(mask,i*n+i+1,(i+1)*n,-10000f); }
            Pointer binding=bind(decoder),map=null,value=null,floatTensor=null,view=null;
            try {
                tensors.forEach((name,tensor)->bind(binding,name,tensor));
                put(binding,"tokens",new long[]{beams,n},tokens,tokens.length,intFactory);
                put(binding,"positions",new long[]{n},positions,n,intFactory);
                put(binding,"mask",new long[]{1,1,n,n},mask,mask.length,floatFactory);
                map=evaluate(decoder,binding);
                value=output(map,"logits");
                var out=new PointerByReference();
                try(var id=Sapi.guid(FLOAT)) { Sapi.ok(Sapi.call(value,0,id,out),"float output"); }
                floatTensor=out.getValue();
                Sapi.ok(Sapi.call(floatTensor,6,out),"read logits");
                view=out.getValue();
                float[] values=new float[51864*beams];
                var count=new IntByReference();
                Sapi.ok(Sapi.call(view,9,0,values.length,values,count),"copy logits");
                if(count.getValue()!=values.length) throw new IllegalStateException("Incomplete token scores");
                return values;
            } finally { Sapi.release(view);Sapi.release(floatTensor);Sapi.release(value);Sapi.release(map);Sapi.release(binding); }
        }
        private void put(Pointer b,String name,long[] shape,Object array,int count,Pointer f) {
            Pointer t=tensor(shape,array,count,f);
            try { bind(b,name,t); } finally { Sapi.release(t); }
        }
        @Override public void close(){ tensors.values().forEach(Sapi::release);tensors.clear(); }
    }
    @Override public void close() {
        Sapi.release(decoder);Sapi.release(encoder);Sapi.release(bindingFactory);Sapi.release(intFactory);Sapi.release(floatFactory);
        decoder=encoder=bindingFactory=intFactory=floatFactory=null;
        if(initialized) { system("RoUninitialize").invokeVoid(new Object[0]);initialized=false; }
    }
}

package com.smart.android.ad_app.google.ima.local.signal.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;

/** Native-free subset of the Java helper receiver's real local state contract.
 * GPU rendering and sensor listener helpers are not supplied by this subset.
 * The independent VM must call its lifecycle setters, rather than inject saved
 * helper outputs. It is not a generic substitute for the original receiver.
 */
public final class DroidGuardHelperState {
    private int runStatus;
    private Object storedBundleObject;
    private Object storedDataObject;
    private GraphicsResult graphics;

    public static final class GraphicsResult {
        public final long first, second;
        public final String description, renderer;
        public GraphicsResult(long first,long second,String description,String renderer){
            this.first=first;this.second=second;this.description=description;this.renderer=renderer;
        }
    }
    /** Called only by the actual independent VM/GPU implementation. */
    public void updateRunStatus(int value){runStatus=value;}
    public void updateGraphics(GraphicsResult value){graphics=value;}
    public int gr(){return runStatus;}
    public Object rb(){return storedBundleObject;}
    public Object rd(){return storedDataObject;}
    public void wb(Object value){storedBundleObject=value;}
    public void wd(Object value){storedDataObject=value;}
    public void n(){graphics=null;}
    public long i(){GraphicsResult value=graphics;return value==null?0:value.first;}
    public long j(){GraphicsResult value=graphics;return value==null?0:value.second;}
    public String h(){GraphicsResult value=graphics;return value==null?null:value.description;}
    public String m(){GraphicsResult value=graphics;return value==null?null:value.renderer;}

    /** k() reads actual filesystem metadata; values are never baked in. */
    public String k() throws IOException,NoSuchAlgorithmException {
        ArrayList<String> entries=new ArrayList<>();
        appendLibraries(new File("/vendor/lib/egl"),entries);
        appendLibraries(new File("/system/lib/egl"),entries);
        Collections.sort(entries);
        entries.add(hashFilePrefix(new File("/system/lib/egl/egl.cfg")));
        return sha1(entries.toString().getBytes());
    }
    private static void appendLibraries(File directory,ArrayList<String> entries) throws IOException,NoSuchAlgorithmException {
        if(directory.isDirectory()) {
            File[] files=directory.listFiles((dir,name)->name.endsWith(".so"));
            // The original does not turn listFiles(null) into an empty list.
            for(File file:files)
                entries.add(file.getName()+"/"+file.length()+"/"+hashFilePrefix(file));
        }
    }
    private static String hashFilePrefix(File file) {
        FileInputStream input=null;
        try {
            input=new FileInputStream(file);
            byte[] prefix=new byte[(int)Math.min(1024L,file.length())];
            input.read(prefix); // One read. Unfilled bytes remain zero, like reference.
            return sha1(prefix);
        }catch(Exception error){return "";}
        finally{if(input!=null)try{input.close();}catch(IOException ignored){}}
    }
    private static String sha1(byte[] bytes)throws NoSuchAlgorithmException {
        MessageDigest digest=null;
        for(int count=0;count<2;count++) {
            try{digest=MessageDigest.getInstance("SHA-1");}catch(NoSuchAlgorithmException ignored){}
            if(digest!=null)break;
        }
        if(digest==null)return "";
        byte[] result=digest.digest(bytes);char[] hex="0123456789abcdef".toCharArray();
        StringBuilder text=new StringBuilder(result.length*2);
        for(byte value:result){text.append(hex[(value&240)>>>4]);text.append(hex[value&15]);}
        return text.toString();
    }

    /** l(byte[])'s conversion stage, before genuine GPU work in g(float[]). */
    public static float[] graphicsInputs(byte[] bytes) {
        if(bytes==null||bytes.length<48)return null;
        ByteBuffer input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result=new float[12];
        for(int index=0;index<12;index++) {
            int value=Math.abs(input.getInt());
            if(value<0)value=0; // Math.abs(Integer.MIN_VALUE) overflows.
            result[index]=value/2.1474836E9f;
        }
        return result;
    }
}

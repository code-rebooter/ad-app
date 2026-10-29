package com.smart.android.ad_app.google.ima.local.signal.android;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.regex.Pattern;

/** Independent ABI selector for the pinned SDK's zzol return-value contract.
 * sdkOsArch must be the actual upstream/patched getter value for this call.
 * Diagnostic logger side effects from the reference SDK are not reproduced.
 */
public final class V2AbiSelector {
    private V2AbiSelector() {}
    public static final class Result {
        public final int selector;
        public final String source;
        public final String sourceFile;
        public final String architecture;
        public final byte[] header;
        Result(int selector,String source,String sourceFile,String architecture,byte[] header) {
            this.selector=selector;this.source=source;this.sourceFile=sourceFile;
            this.architecture=architecture;this.header=header==null?null:header.clone();
        }
        public boolean permitsVm(){return permitted(selector);}
        public String cacheSuffix(){return Integer.toString(selector-1);}
    }
    public static boolean permitted(int selector) {
        return selector==3||selector==5||selector==6||selector==7||selector==8;
    }
    public static Result select(Context context,String sdkOsArch) {
        File directory=new File(context.getApplicationInfo().dataDir,"lib");
        if(directory.exists()) {
            final Pattern pattern=Pattern.compile(".*\\.so$",Pattern.CASE_INSENSITIVE);
            File[] files=directory.listFiles((dir,name)->pattern.matcher(name).matches());
            if(files!=null&&files.length!=0) {
                File file=files[0];
                byte[] header=new byte[20];
                try(FileInputStream input=new FileInputStream(file)) {
                    // One read, as in the SDK; a short read is not retried.
                    if(input.read(header)!=20)
                        return new Result(1,"library-short-read",file.getPath(),null,null);
                    return new Result(fromHeader(header),"first-library-header",file.getPath(),null,header);
                }catch(IOException error) {
                    return new Result(1,"library-io-error",file.getPath(),null,null);
                }
            }
        }
        String architecture=architecture(sdkOsArch);
        return new Result(fromArchitecture(architecture),"architecture-fallback",null,architecture,null);
    }
    private static String architecture(String sdkOsArch) {
        // The original set membership is case sensitive, while mapping below is not.
        if(sdkOsArch!=null&&!sdkOsArch.isEmpty()&&(sdkOsArch.equals("i686")||sdkOsArch.equals("armv71")))return sdkOsArch;
        try {
            Field field=Build.class.getField("SUPPORTED_ABIS");
            String[] abis=(String[])field.get(null);
            if(abis!=null&&abis.length>0)return abis[0];
        }catch(NoSuchFieldException|IllegalAccessException ignored){}
        return Build.CPU_ABI!=null?Build.CPU_ABI:Build.CPU_ABI2;
    }
    public static int fromArchitecture(String architecture) {
        if(architecture==null||architecture.isEmpty())return 1;
        if(architecture.equalsIgnoreCase("i686")||architecture.equalsIgnoreCase("x86"))return 5;
        if(architecture.equalsIgnoreCase("x86_64"))return 7;
        if(architecture.equalsIgnoreCase("arm64-v8a"))return 6;
        if(architecture.equalsIgnoreCase("armeabi-v7a")||architecture.equalsIgnoreCase("armv71"))return 3;
        if(architecture.equalsIgnoreCase("riscv64"))return 8;
        return 1;
    }
    public static int fromHeader(byte[] header) {
        if(header.length!=20)throw new IllegalArgumentException("SDK header input is exactly 20 bytes");
        if(header[5]==2)return 1;
        // No ELF magic/class validation appears in the reference method.
        int machine=(header[18]&255)|((header[19]&255)<<8);
        switch(machine){case 3:return 5;case 40:return 3;case 62:return 7;case 183:return 6;case 243:return 8;default:return 1;}
    }
}

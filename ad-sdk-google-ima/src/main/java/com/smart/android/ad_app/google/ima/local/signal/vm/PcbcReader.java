package com.smart.android.ad_app.google.ima.local.signal.vm;

import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Independent valid-range PCBC decoding; no native or IMA dependency. */
public final class PcbcReader {
    public static final long INITIAL_KEY=0x66905a037039b34fL;
    private final byte[] program;
    private byte[] nonce;
    private int cachedBlock=-1;
    private byte[] cachedStream;
    private Cipher aes;
    public static final class Read {
        public final int next;public final byte[] bytes;
        Read(int next,byte[] bytes){this.next=next;this.bytes=bytes;}
        public long unsigned(){long value=0;for(int i=0;i<bytes.length;i++)value|=(bytes[i]&255L)<<(8*i);return value;}
    }
    public PcbcReader(byte[] program){this.program=program.clone();setKey(INITIAL_KEY);}
    public int length(){return program.length;}
    public void setKey(long value){
        byte[] key=new byte[16];for(int i=0;i<16;i++)key[i]=(byte)(value>>>(8*(i&7)));
        try{aes=Cipher.getInstance("AES/ECB/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"));}
        catch(Exception error){throw new IllegalStateException("AES128 ECB backend unavailable",error);}
        cachedBlock=-1;cachedStream=null;nonce=null;
    }
    public Read read(int position,int count){
        if(position<0||count<0||(long)position+count>program.length)throw new IllegalArgumentException("PCBC input range");
        if(count==0)return new Read(position,new byte[0]);
        if(cachedBlock==-1){if((long)position+4>program.length)throw new IllegalArgumentException("PCBC nonce range");nonce=Arrays.copyOfRange(program,position,position+4);position+=4;}
        int end=position+count;if(end<position||end>program.length)throw new IllegalArgumentException("PCBC decoded range");
        byte[] out=new byte[count];
        for(int i=position;i<end;){
            int block=i>>>4;
            if(block!=cachedBlock){
                byte[] counter=new byte[16];System.arraycopy(nonce,0,counter,0,4);
                for(int j=0;j<4;j++)counter[4+j]=(byte)(block>>>(8*j));
                try{cachedStream=aes.doFinal(counter);}catch(Exception error){throw new IllegalStateException(error);}
                cachedBlock=block;
            }
            int limit=Math.min(end,(block+1)<<4);
            while(i<limit){out[i-position]=(byte)(program[i]^cachedStream[i&15]);i++;}
        }
        return new Read(end,out);
    }
}

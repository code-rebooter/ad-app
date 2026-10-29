package com.smart.android.ad_app.google.ima.local.signal;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independent pinned slot16 transform; no SDK outputs are accepted as inputs.
 * Existing bytes are the VM's own feedback buffer. The initial prefix must be
 * collected from its real RNG source. Plaintext is newly serialized source data.
 */
public final class Slot16Codec {
    private Slot16Codec() {}
    private static final int DELTA=0xd1bd48ad;
    private static int read32(byte[] bytes,int offset){return (bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16)|(bytes[offset+3]<<24);}
    private static void write32(byte[] bytes,int offset,int value){for(int i=0;i<4;i++)bytes[offset+i]=(byte)(value>>>(8*i));}
    private static byte[] keyBytes(long key){byte[] bytes=new byte[8];for(int i=0;i<8;i++)bytes[i]=(byte)(key>>>(8*i));return bytes;}
    public static byte[] previousBlockStream(byte[] block,long key){
        if(block.length!=8)throw new IllegalArgumentException("Exactly eight previous ciphertext bytes required");
        int left=read32(block,0),right=read32(block,4),sum=0;
        int[] words={(int)key,(int)Long.rotateLeft(key,16),(int)(key>>>32),(int)(key>>>16)};
        for(int i=0;i<32;i++){
            left+=(((right<<4)^(right>>>5))+right)^(sum+words[sum&3]);
            sum+=DELTA;
            right+=(((left<<4)^(left>>>5))+left)^(sum+words[(sum>>>11)&3]);
        }
        byte[] result=new byte[8];write32(result,0,right);write32(result,4,left);return result;
    }
    public static byte[] appendCiphertext(byte[] existing,byte[] incoming,long key){
        if(existing.length<8)throw new IllegalArgumentException("The actual VM prefix contains at least eight random bytes");
        long combined=(long)existing.length+incoming.length;if(combined>Integer.MAX_VALUE)throw new IllegalArgumentException("Buffer size overflow");
        byte[] result=Arrays.copyOf(existing,(int)combined);
        int previousBlock=-1;byte[] stream=null;
        for(int i=0;i<incoming.length;i++){
            int position=existing.length+i,block=position>>>3;
            if(block!=previousBlock){int from=(block<<3)-8;stream=previousBlockStream(Arrays.copyOfRange(result,from,from+8),key);previousBlock=block;}
            result[position]=(byte)(incoming[i]^stream[position&7]);
        }
        return result;
    }
    /** Rebuilds the standard HMAC from genuine appended plaintext for a portable
     * nondestructive digest; it does not require provider-specific Mac.clone(). */
    public static final class MacState {
        private boolean initialized;
        private long retainedKey;
        private final ByteArrayOutputStream plaintext=new ByteArrayOutputStream();
        public void update(int previousBufferLength,byte[] incoming,long currentSlot15Key){
            if(previousBufferLength<9||!initialized){retainedKey=currentSlot15Key;plaintext.reset();initialized=true;}
            plaintext.write(incoming,0,incoming.length);
        }
        public byte[] digest()throws GeneralSecurityException{
            if(!initialized)throw new IllegalStateException("No genuine append has initialized the HMAC");
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(keyBytes(retainedKey),"HmacSHA256"));return mac.doFinal(plaintext.toByteArray());
        }
        public MacState copy(){MacState value=new MacState();value.initialized=initialized;value.retainedKey=retainedKey;byte[] bytes=plaintext.toByteArray();value.plaintext.write(bytes,0,bytes.length);return value;}
        public boolean initialized(){return initialized;}
        public long retainedKey(){if(!initialized)throw new IllegalStateException("MAC absent");return retainedKey;}
        public int plaintextByteCount(){return plaintext.size();}
    }
}

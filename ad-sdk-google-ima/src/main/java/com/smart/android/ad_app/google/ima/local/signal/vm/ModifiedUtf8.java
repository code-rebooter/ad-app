package com.smart.android.ad_app.google.ima.local.signal.vm;
import java.io.ByteArrayOutputStream;
/** JNI modified UTF8, encoded as UTF16 units with no four-byte sequences. */
public final class ModifiedUtf8 {
 private ModifiedUtf8(){}
 public static byte[] encode(String text){ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<text.length();i++){int c=text.charAt(i);if(c>0&&c<128)out.write(c);else if(c<2048){out.write(0xc0|(c>>>6));out.write(0x80|(c&63));}else{out.write(0xe0|(c>>>12));out.write(0x80|((c>>>6)&63));out.write(0x80|(c&63));}}return out.toByteArray();}
 public static String decode(byte[] bytes){StringBuilder out=new StringBuilder();for(int i=0;i<bytes.length;){int a=bytes[i++]&255,c;if(a<128)c=a;else if((a&0xe0)==0xc0){if(i>=bytes.length)throw new IllegalArgumentException("truncated MUTF8");c=((a&31)<<6)|(bytes[i++]&63);}else if((a&0xf0)==0xe0){if(i+1>=bytes.length)throw new IllegalArgumentException("truncated MUTF8");c=((a&15)<<12)|((bytes[i++]&63)<<6)|(bytes[i++]&63);}else throw new IllegalArgumentException("unsupported MUTF8 leading byte");out.append((char)c);}return out.toString();}
}

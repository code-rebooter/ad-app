package com.smart.android.ad_app.google.ima.local.portable;
/** RFC4648 encoder, no platform/API26 Base64 dependency. */
public final class Base64Compat {
 private static final Encoder STANDARD=new Encoder("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",true);
 private static final Encoder URL=new Encoder("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_",true);
 private Base64Compat(){}
 public static Encoder getEncoder(){return STANDARD;}public static Encoder getUrlEncoder(){return URL;}
 public static final class Encoder {
  private final String alphabet;private final boolean padding;
  private Encoder(String alphabet,boolean padding){this.alphabet=alphabet;this.padding=padding;}
  public Encoder withoutPadding(){return padding?new Encoder(alphabet,false):this;}
  public String encodeToString(byte[] bytes){if(bytes==null)throw new NullPointerException();long size=((long)bytes.length+2)/3*4;if(!padding&&bytes.length%3!=0)size-=3-bytes.length%3;if(size>Integer.MAX_VALUE)throw new OutOfMemoryError("encoded data too large");StringBuilder out=new StringBuilder((int)size);int i=0;while(i+2<bytes.length){int word=((bytes[i++]&255)<<16)|((bytes[i++]&255)<<8)|(bytes[i++]&255);out.append(alphabet.charAt(word>>>18)).append(alphabet.charAt(word>>>12&63)).append(alphabet.charAt(word>>>6&63)).append(alphabet.charAt(word&63));}int left=bytes.length-i;if(left>0){int word=(bytes[i++]&255)<<16;if(left==2)word|=(bytes[i]&255)<<8;out.append(alphabet.charAt(word>>>18)).append(alphabet.charAt(word>>>12&63));if(left==2)out.append(alphabet.charAt(word>>>6&63));else if(padding)out.append('=');if(padding)out.append('=');}return out.toString();}
 }
}

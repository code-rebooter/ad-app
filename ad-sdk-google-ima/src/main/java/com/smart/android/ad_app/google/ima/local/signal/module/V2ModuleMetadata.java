package com.smart.android.ad_app.google.ima.local.signal.module;
import java.io.*;import java.nio.charset.*;import java.util.*;
/** Independent zzlf five-field metadata reader. Unknown data is retained as source bytes. */
public final class V2ModuleMetadata {
 public final String moduleId,moduleToken;public final long rawLong3,rawLong4,rawLong5;public final int presenceMask;
 private final byte[] raw;private final List<byte[]> unknown;
 private V2ModuleMetadata(String id,String token,long a,long b,long c,int mask,byte[] raw,List<byte[]> unknown){moduleId=id;moduleToken=token;rawLong3=a;rawLong4=b;rawLong5=c;presenceMask=mask;this.raw=raw.clone();this.unknown=unknown;}
 public static final class Malformed extends IllegalArgumentException {public Malformed(String s){super(s);}}
 public byte[] rawBytes(){return raw.clone();}
 public List<byte[]> unknownFields(){List<byte[]> copy=new ArrayList<byte[]>();for(byte[] b:unknown)copy.add(b.clone());return Collections.unmodifiableList(copy);}
 public static V2ModuleMetadata parse(byte[] source){
  if(source==null)throw new NullPointerException("metadata bytes");Reader r=new Reader(source);String id="",token="";long a=0,b=0,c=0;int mask=0;List<byte[]> unknown=new ArrayList<byte[]>();
  while(r.pos<source.length){int start=r.pos;long tag=r.varint();int field=r.field(tag),wire=(int)(tag&7);if((field==1||field==2)&&wire==2){String s=r.string();if(field==1){id=s;mask|=1;}else{token=s;mask|=2;}}else if(field>=3&&field<=5&&wire==0){long value=r.varint();mask|=1<<(field-1);if(field==3)a=value;else if(field==4)b=value;else c=value;}else{r.skip(field,wire,0);unknown.add(Arrays.copyOfRange(source,start,r.pos));}}
  return new V2ModuleMetadata(id,token,a,b,c,mask,source,unknown);
 }
 /** New provider catches malformed proto, bad hex and runtime failures. */
 public static V2ModuleMetadata fromNewCacheHexOrNull(String hex){if(hex==null)return null;try{return parse(unhex(hex));}catch(RuntimeException invalid){return null;}}
 /** Old provider rejects empty, catches proto errors but lets invalid hex propagate. */
 public static V2ModuleMetadata fromOldCacheHexOrNull(String hex){if(hex==null||hex.length()==0)return null;byte[] raw=unhex(hex);try{return parse(raw);}catch(Malformed invalid){return null;}}
 /** Exact zzpd.zzf/zzow cache projection: all five getter values explicitly set. */
 public byte[] serializeForCache(){ByteArrayOutputStream b=new ByteArrayOutputStream();bytes(b,1,moduleId.getBytes(StandardCharsets.UTF_8));bytes(b,2,moduleToken.getBytes(StandardCharsets.UTF_8));integer(b,3,rawLong3);integer(b,4,rawLong4);integer(b,5,rawLong5);return b.toByteArray();}
 public byte[] serializePreservingPresenceAndUnknown(){ByteArrayOutputStream out=new ByteArrayOutputStream();if((presenceMask&1)!=0)bytes(out,1,moduleId.getBytes(StandardCharsets.UTF_8));if((presenceMask&2)!=0)bytes(out,2,moduleToken.getBytes(StandardCharsets.UTF_8));if((presenceMask&4)!=0)integer(out,3,rawLong3);if((presenceMask&8)!=0)integer(out,4,rawLong4);if((presenceMask&16)!=0)integer(out,5,rawLong5);for(byte[] rawUnknown:unknown){Reader r=new Reader(rawUnknown);canonicalUnknown(r,out,0);}return out.toByteArray();}
 private static void canonicalUnknown(Reader r,ByteArrayOutputStream out,int depth){long tag=r.varint();int field=r.field(tag),wire=(int)(tag&7);varint(out,tag);switch(wire){case 0:varint(out,r.varint());return;case 1:r.require(8);out.write(r.bytes,r.pos,8);r.pos+=8;return;case 2:int n=r.length();varint(out,n);out.write(r.bytes,r.pos,n);r.pos+=n;return;case 3:if(depth>100)throw new Malformed("Excessive group nesting");while(r.pos<r.bytes.length){int start=r.pos;long nested=r.varint();int child=r.field(nested);r.pos=start;if((nested&7)==4){if(child!=field)throw new Malformed("Mismatched group end");varint(out,r.varint());return;}canonicalUnknown(r,out,depth+1);}throw new Malformed("Unterminated group");case 5:r.require(4);out.write(r.bytes,r.pos,4);r.pos+=4;return;default:throw new Malformed("Invalid unknown wire type");}}
 public String cacheHex(){return hex(serializeForCache());}
 public boolean needsRefreshAt(long actualWallMillis){return rawLong3-actualWallMillis/1000<3600;}
 public static byte[] unhex(String text){if((text.length()&1)!=0)throw new IllegalArgumentException("Hex string has odd length");byte[] out=new byte[text.length()/2];for(int i=0;i<out.length;i++){out[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);}return out;}
 public static String hex(byte[] b){char[] d="0123456789abcdef".toCharArray(),out=new char[b.length*2];for(int i=0;i<b.length;i++){out[2*i]=d[(b[i]>>>4)&15];out[2*i+1]=d[b[i]&15];}return new String(out);}
 private static void integer(ByteArrayOutputStream b,int field,long value){varint(b,(long)field<<3);varint(b,value);}private static void bytes(ByteArrayOutputStream b,int field,byte[] value){varint(b,((long)field<<3)|2);varint(b,value.length);b.write(value,0,value.length);}private static void varint(ByteArrayOutputStream b,long value){while((value&~127L)!=0){b.write(((int)value&127)|128);value>>>=7;}b.write((int)value);}
 private static final class Reader{
  final byte[] bytes;int pos;Reader(byte[] b){bytes=b;}
  long varint(){long out=0;for(int shift=0;shift<64;shift+=7){require(1);int x=bytes[pos++]&255;out|=(long)(x&127)<<shift;if((x&128)==0)return out;}throw new Malformed("Malformed varint");}
  int field(long tag){long f=tag>>>3;if(f==0||f>0x1fffffffL)throw new Malformed("Invalid protobuf tag");return(int)f;}
  int length(){long n=varint();if(n<0||n>Integer.MAX_VALUE)throw new Malformed("Invalid protobuf length");require((int)n);return(int)n;}
  void require(int count){if(count<0||count>bytes.length-pos)throw new Malformed("Truncated metadata");}
  String string(){int n=length();String out=new String(bytes,pos,n,StandardCharsets.UTF_8);pos+=n;return out;}
  void skip(int f,int wire,int depth){if(depth>100)throw new Malformed("Excessive protobuf nesting");switch(wire){case 0:varint();return;case 1:require(8);pos+=8;return;case 2:int n=length();pos+=n;return;case 3:while(pos<bytes.length){long tag=varint();int child=field(tag),w=(int)(tag&7);if(w==4){if(child!=f)throw new Malformed("Mismatched group end");return;}skip(child,w,depth+1);}throw new Malformed("Unterminated group");case 5:require(4);pos+=4;return;default:throw new Malformed("Invalid wire type");}}
 }
}

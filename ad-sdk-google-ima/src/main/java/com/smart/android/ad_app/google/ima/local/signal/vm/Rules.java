package com.smart.android.ad_app.google.ima.local.signal.vm;
import java.io.ByteArrayOutputStream;

/** Recovered stable VM instruction primitives from the pinned native code. */
public final class Rules {
 private Rules(){}
 public static byte[] little(long value,int size){byte[] out=new byte[size];for(int i=0;i<size;i++)out[i]=(byte)(value>>>(i*8));return out;}
 public static byte[] concat(byte[] first,byte[] second){byte[] out=new byte[first.length+second.length];System.arraycopy(first,0,out,0,first.length);System.arraycopy(second,0,out,first.length,second.length);return out;}
 public static long mask(long key,int flags,int index){int shift=((flags&0x10)|index)&(~flags|0x3e);return Long.rotateRight(key,-shift);}
 public static byte[] decodeString(byte[] encoded,long key,int flags,int index){long mask=mask(key,flags,index);byte[] out=new byte[encoded.length];for(int i=0;i<out.length;i++)out[i]=(byte)(encoded[i]^(mask>>>(8*(i&7))));return out;}
 public static byte[] varint(long value){ByteArrayOutputStream out=new ByteArrayOutputStream();while((value&~127L)!=0){out.write(((int)value&127)|128);value>>>=7;}out.write((int)value);return out.toByteArray();}
 public static byte[] protobuf(byte[] tag,Value value){byte[] body;switch(value.kind){case 0:body=varint((int)value.scalar);break;case 1:body=concat(varint(value.bytes.length),value.bytes);break;case 2:body=little(value.scalar,8);break;case 4:case 5:body=varint(value.scalar);break;default:throw new IllegalArgumentException("Java references cannot be serialized by this handler");}return concat(tag,body);}
 private static long[] mix(long[] a,int rounds){a=a.clone();for(int i=0;i<rounds;i++){a[4]^=a[5]&a[6];a[7]^=a[1]|a[2];a[1]+=a[6]^a[7];a[6]+=a[7]&a[0];a[5]=a[0]+a[5]-a[4];a[0]^=a[1]+a[7];a[3]=a[2]^a[3]^a[6];a[2]=a[2]-a[4]-a[3];}return a;}
 private static byte[] packed(long[]... chunks){byte[] out=new byte[256];int position=0;for(long[] chunk:chunks)for(long word:chunk)for(int i=0;i<8;i++)out[position++]=(byte)(word>>>(8*i));if(position!=256)throw new AssertionError();return out;}
 private static final byte[] A=packed(
 new long[]{0x89e37f72abec2418L,0x1375f78b7a82c769L,0x746b220088d61ae6L,0xb4b7a598f87097bfL,0x6585d9ba0327ef30L,0x62968dffaecbdcc1L,0x0cf95a4b905c7d4dL,0x1f0b59c899e5c4b2L},
 mix(new long[]{0x4e93e6aa2040b7f7L,0x1f3e9308caea65acL,0x5935869a26266c59L,0xe332b7514782800dL,0x6ffc45ce71efc0a9L,0xb36bd77e5c87b50dL,0xe1d46b4e81327a0fL,0xd9de58e62b1856d8L},7),
 mix(new long[]{0xcbf451e29c989440L,0xebfad1576e162eb7L,0x2c67e669b85029afL,0x913ca963116202b7L,0x18eb2dc5abef8346L,0x52de33ac01bfe2c3L,0xf08dab742a7bc153L,0x4c0dece5a18ad9d6L},10),
 mix(new long[]{0x55af247b8e3e696eL,0x3bce09dcddcac4d4L,0x181c1471f3637cfaL,0x5f1ad74800a087f7L,0x69ac37355b7c29afL,0x08b0341122c789dcL,0xfd2699a1b714d13dL,0x0e85aff6b4e89ebfL},15));
 private static final byte[] B=packed(
 mix(new long[]{0x4b6e2f44030749afL,0xbd49acb8ba07b3a1L,0x6ef403872079eb76L,0x5e1c5dd82039b9eaL,0x35918cd3bf5f1954L,0xc2f33b8c3c4c3ccdL,0xfff8739f41e0abc2L,0xcbda6c775f8e3b6dL},16),
 mix(new long[]{0xa790fe7706bc0d01L,0xcf164015f4f63b63L,0x848ca650b98f2778L,0xf32da56f99337fb3L,0x1221ff87c347d21eL,0x01352edb9c9521f4L,0xc14263113bbae139L,0x45154db9807056cdL},17),
 new long[]{0x9d66c2d26d5999f6L,0x72b0687184d1a7c8L,0x17aca8168d97ab35L,0xe2ef90db4e63feecL,0xa426ed24d07f4c10L,0xfd8c6b22b612d33eL,0x6f75b88b3baa0581L,0x34da56c49f04c983L},
 mix(new long[]{0x580ba587b0adbc18L,0x2f1062467b84f254L,0x3859f02f83fa5b63L,0xa3bb6b2ea01950a1L,0x6f67efee1b073675L,0xab4b024726f3e772L,0xdeaa4edad4b5051fL,0x148e0e69da4eea8aL},14));
 private static int[] power(byte[] permutation,int exponent){int[] out=new int[256];boolean[] seen=new boolean[256];for(int first=0;first<256;first++){if(seen[first])continue;int[] cycle=new int[256];int count=0,v=first;while(!seen[v]){seen[v]=true;cycle[count++]=v;v=permutation[v]&255;}int shift=exponent%count;for(int i=0;i<count;i++)out[cycle[i]]=cycle[(i+shift)%count];}return out;}
 public static int[] permute(int[] table,int first,int second,int third){int[] left=power(A,(third+0x3d40)&65535),middle=power(B,(first-0x65da)&65535),right=power(A,second^0xf8ae),out=new int[256];for(int i=0;i<256;i++)out[i]=table[left[middle[right[i]]]];return out;}
}

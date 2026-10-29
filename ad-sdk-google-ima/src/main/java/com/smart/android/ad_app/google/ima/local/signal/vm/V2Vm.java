package com.smart.android.ad_app.google.ima.local.signal.vm;

import com.smart.android.ad_app.google.ima.local.signal.Slot16Codec;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Independent partial V2 VM. Only recovered handlers execute. Unknown native
 * instructions fail with PC and key; no fallback or signal result is invented.
 * The stable 524-instruction bootstrap path is the initial port target. */
public final class V2Vm {
 public static final class UnsupportedInstruction extends Exception {public UnsupportedInstruction(String message){super(message);}}
 public static final class Instruction {public final int before,after,opcode,dispatchIndex,key;Instruction(int before,int after,int opcode,int index,int key){this.before=before;this.after=after;this.opcode=opcode;dispatchIndex=index;this.key=key;}}
 public final PcbcReader reader;
 private final byte[] program;
 private int[] table;
 private final List<Map<Integer,Value>> frames=new ArrayList<Map<Integer,Value>>();
 private final TreeMap<Long,Byte> memory=new TreeMap<Long,Byte>();
 private final Map<Long,MessageDigest> sha1=new HashMap<Long,MessageDigest>();
 private final Map<Integer,Slot16Codec.MacState> macs=new HashMap<Integer,Slot16Codec.MacState>();
 private long allocation=0x10000000L;
 private final JavaSources javaSources;
 private final int runtimeEnvironment;
 private final Map<Integer,Value> originalBindings=new HashMap<Integer,Value>();
 private final long frameKey=0x89f6d88a8b85b5c5L;
 private final int frameFlags=0xcb96b93f;
 public long steps;public Instruction last;
 public V2Vm(byte[] program,int[] genuineInitialTable,Map<Integer,Value> initialSlots,long symbolicStateAddress,JavaSources javaSources,int runtimeEnvironment){
  if(genuineInitialTable.length!=256)throw new IllegalArgumentException("256-word source module table required");
  this.program=program.clone();reader=new PcbcReader(program);table=genuineInitialTable.clone();this.javaSources=javaSources;this.runtimeEnvironment=runtimeEnvironment;frames.add(new HashMap<Integer,Value>());
  int[] zero={2,5,9,18,22,26,29,33,36,37,41};for(int index:zero)set(index,Value.scalar(0,0));
  set(0,Value.scalar(4,0x14ce0L^0x43665b19038b45beL));set(4,Value.scalar(4,0x70e8));set(8,Value.scalar(4,0x55550));set(15,Value.scalar(5,0));
  for(int index:new int[]{17,20,25})set(index,Value.scalar(4,0));for(int index:new int[]{6,11,13,27})set(index,Value.bytes(new byte[0]));
  set(31,Value.scalar(5,PcbcReader.INITIAL_KEY));set(32,Value.scalar(4,symbolicStateAddress+0x1664));set(40,Value.scalar(0,1));
  if(initialSlots!=null){for(Map.Entry<Integer,Value> item:initialSlots.entrySet())set(item.getKey(),item.getValue());for(int index:new int[]{1,3,23,42})if(initialSlots.containsKey(index))originalBindings.put(index,initialSlots.get(index));}
 }
 public Value get(int index){for(int i=frames.size()-1;i>=0;i--){Value value=frames.get(i).get(index);if(value!=null)return value;}throw new IllegalStateException("Undefined source VM slot "+index);}
 public void set(int index,Value value){if(index<0||index>255)throw new IllegalArgumentException("slot range");frames.get(frames.size()-1).put(index,value);if(index==31){require(value,5);reader.setKey(value.scalar);}}
 public int position(){Value value=get(26);require(value,0);return (int)value.scalar;}
 public int[] opcodeTable(){return table.clone();}
 public Map<Integer,Value> snapshot(){Map<Integer,Value> out=new TreeMap<Integer,Value>();for(Map<Integer,Value> frame:frames)out.putAll(frame);return out;}
 public Map<Long,Byte> memorySnapshot(){return new TreeMap<Long,Byte>(memory);}
 public Map<Long,String> allocationSnapshot(){Map<Long,String> out=new TreeMap<Long,String>();for(Long address:sha1.keySet())out.put(address,sha1.get(address)==null?"SHA1-freed":"SHA1");return out;}
 public boolean enterXss(Object query,int[] initialTable){return enterXss(query,initialTable,new String[0]);}
 public boolean enterXss(Object query,int[] initialTable,String[] genuineRequestArray){
  if(runtimeEnvironment!=2)return false;return enterGeneration(query,initialTable,genuineRequestArray,0);
 }
 public boolean enterClose(int[] initialTable){return enterGeneration(null,initialTable,null,1);}
 private boolean enterGeneration(Object query,int[] initialTable,String[] actualArray,int mode){
  Value entry=get(36);require(entry,0);if(entry.scalar==0)return false;
  for(int index:new int[]{1,3,23,42}){Value original=originalBindings.get(index);if(original==null)throw new IllegalStateException("Actual constructor binding required for xss slot "+index);set(index,original);}
  set(14,Value.object(query));set(38,Value.object(actualArray));set(19,Value.object(null));set(34,Value.object(null));
  set(18,Value.scalar(0,mode));set(29,Value.scalar(0,0));set(31,Value.scalar(5,PcbcReader.INITIAL_KEY));set(26,entry);table=initialTable.clone();return true;
 }
 private static void require(Value value,int kind){if(value.kind!=kind)throw new IllegalArgumentException("slot type "+value.kind+" requires "+kind);}
 private byte[] read(int count){PcbcReader.Read result=reader.read(position(),count);set(26,Value.scalar(0,result.next));return result.bytes;}
 private long uint(int count){byte[] bytes=read(count);long value=0;for(int i=0;i<count;i++)value|=(bytes[i]&255L)<<(8*i);return value;}
 private int u8(){return (int)uint(1);}
 private int b(int x){return x&255;}
 private byte[] seeded(long seed){int size=((int)seed-(int)uint(2))&65535;byte[] raw=read(size);for(int i=0;i<size;i++)raw[i]=(byte)((seed>>>(8*(i&7)))-raw[i]);return raw;}
 private JavaSources javaSource()throws UnsupportedInstruction{if(javaSources==null)throw new UnsupportedInstruction("Actual local JavaSources required at PC "+position());return javaSources;}
 private String text(Value value){require(value,1);return ModifiedUtf8.decode(value.bytes);}
 private Value[] arguments(String signature,long seed,boolean add){List<String> descriptors=ReflectionSources.argumentDescriptors(signature);byte[] raw=read(descriptors.size());Value[] values=new Value[raw.length];for(int i=0;i<raw.length;i++){int key=(int)(seed>>>(8*(i&7)))&255;values[i]=get(b(add?(raw[i]&255)+key:key-(raw[i]&255)));}return values;}
 private long newAllocation(int size){long address=allocation;allocation=(address+size+15)&~15L;return address;}
 private static int byteCompare(byte[] a,byte[] c){for(int i=0;i<Math.min(a.length,c.length);i++){int d=(a[i]&255)-(c[i]&255);if(d!=0)return d;}return a.length-c.length;}
 private byte[] readTag(){ByteArrayOutputStream out=new ByteArrayOutputStream();long seed=0xa453ba647205a815L;for(int i=0;i<10;i++){int value=b(u8()+(int)(seed>>>(8*(i&7))));out.write(value);if(value<128)return out.toByteArray();}throw new IllegalArgumentException("tag varint bound");}
 public Instruction step()throws Exception{
  int before=position(),opcode=b(u8()-0x16),index=opcode,scan=0;while(table[index]==0){index=b(index-1);if(++scan==256)throw new IllegalStateException("empty opcode table");}int key=table[index];
  last=new Instruction(before,position(),opcode,index,key);
  int a,c,d,e,f;long raw;Value first,second,value;String descriptor,name;Object object;
  switch(key){
  case 0x6417:raw=uint(4);d=u8()^0xb4;set(d,Value.scalar(0,0x0d51159bL-raw));break;
  case 0xfab4:raw=uint(8);d=b(u8()-0x33);set(d,Value.scalar(4,raw+0x04e10bb543b5fa8cL));break;
  case 0x7ce6:d=b(u8()-0x10);raw=uint(8);set(d,Value.scalar(2,raw+0x56e867b4f71d8c3dL));break;
  case 0xd93e:a=u8();d=b(u8()+0x57);set(d,Value.scalar(0,(byte)(1-a)));break;
  case 0x1fa1:raw=uint(8);d=u8()^0xb5;set(d,Value.scalar(5,0x3641330361f49843L-raw));break;
  case 0xe537:{byte[] encoded=seeded(0x89fb7b887edb6628L);d=b(u8()+2);set(d,Value.bytes(Rules.decodeString(encoded,frameKey,frameFlags,d)));break;}
  case 0xfef1:a=(int)uint(2);c=(int)uint(2);d=(int)uint(2);table=Rules.permute(table,a,c,d);break;
  case 0x334c:a=b(0x5e-u8());c=u8()^0x5b;d=u8()^0x5e;first=get(a);second=get(c);if(first.kind!=second.kind||(first.kind!=0&&first.kind!=4&&first.kind!=5))throw new IllegalArgumentException("XOR types");set(d,Value.scalar(first.kind,first.scalar^second.scalar));break;
  case 0xca7d:d=b(0xe9-u8());a=u8()^0xf2;c=b(0xd0-u8());first=get(c);second=get(a);if((first.kind!=0&&first.kind!=5)||(second.kind!=0&&second.kind!=5))throw new IllegalArgumentException("ROR types");set(d,Value.scalar(first.kind,first.kind==0?Integer.rotateRight((int)first.scalar,(int)second.scalar):Long.rotateRight(first.scalar,(int)second.scalar)));break;
  case 0xe827:a=b(0xe7-u8());c=u8()^0xd2;d=b(0x31-u8());first=get(a);second=get(c);if(first.kind==second.kind&&(first.kind==0||first.kind==5))value=Value.scalar(first.kind,first.scalar+second.scalar);else if(first.kind==2&&second.kind==2)value=Value.doubleValue(first.asDouble()+second.asDouble());else if(first.kind==4&&second.kind==0)value=Value.scalar(4,first.scalar+(int)second.scalar);else throw new IllegalArgumentException("ADD types");set(d,value);break;
  case 0x22de:a=u8()^0xbb;c=b(8-u8());d=b(u8()-0x3a);first=get(a);second=get(c);if(first.kind!=second.kind||(first.kind!=0&&first.kind!=5))throw new IllegalArgumentException("REM types");set(d,Value.scalar(first.kind,first.signed()%second.signed()));break;
  case 0x9907:a=u8()^0xa7;d=b(-0x2a-u8());c=b(u8()+0x65);e=u8()^0xa2;f=b(-u8()-0x5a);first=get(a);second=get(c);if(first.kind!=second.kind||first.kind!=get(e).kind||first.kind!=get(f).kind||(first.kind!=0&&first.kind!=5))throw new IllegalArgumentException("SUM OR types");set(d,Value.scalar(first.kind,(first.scalar+second.scalar)|0xdb2057fda2388346L));break;
  case 0xa49c:a=u8()^0xcc;c=b(-u8()-0x75);first=get(a);second=get(c);require(second,0);if(first.truth())set(26,second);break;
  case 0xf786:d=b(u8()+0x18);c=b(u8()-0x40);a=b(u8()+0x4d);first=get(a);second=get(c);if(first.kind!=second.kind)throw new IllegalArgumentException("EQ types");boolean equal;switch(first.kind){case 0:case 4:case 5:equal=first.scalar==second.scalar;break;case 1:equal=Arrays.equals(first.bytes,second.bytes);break;case 2:equal=first.asDouble()==second.asDouble();break;case 3:equal=first.object==second.object;break;default:throw new IllegalArgumentException("EQ kind");}set(d,Value.scalar(0,equal?1:0));break;
  case 0x8ae1:a=b(u8()-0x39);c=b(-u8()-0x66);d=b(0x6b-u8());first=get(a);second=get(c);if(first.kind==second.kind&&(first.kind==0||first.kind==4||first.kind==5))value=Value.scalar(first.kind,first.scalar-second.scalar);else if(first.kind==2&&second.kind==2)value=Value.doubleValue(first.asDouble()-second.asDouble());else if(first.kind==4&&second.kind==0)value=Value.scalar(4,first.scalar-(int)second.scalar);else throw new IllegalArgumentException("SUB types");set(d,value);break;
  case 0x29fa:d=b(0x4c-u8());a=b(0x87-u8());c=b(u8()+0x5e);first=get(a);second=get(c);if(first.kind!=second.kind)throw new IllegalArgumentException("MUL types");if(first.kind==2)value=Value.doubleValue(first.asDouble()*second.asDouble());else if(first.kind==0||first.kind==5)value=Value.scalar(first.kind,first.scalar*second.scalar);else throw new IllegalArgumentException("MUL kind");set(d,value);break;
  case 0xe181:c=b(-u8()-0x63);a=b(0x4e-u8());d=b(u8()+0x77);first=get(a);second=get(c);if(first.kind!=second.kind||(first.kind!=0&&first.kind!=5))throw new IllegalArgumentException("logical AND types");set(d,Value.scalar(first.kind,first.scalar!=0&&second.scalar!=0?1:0));break;
  case 0xb064:c=u8()^0x7d;a=b(-u8()-0x1e);d=b(u8()+0x30);first=get(a);second=get(c);if(first.kind!=second.kind||(first.kind!=0&&first.kind!=4&&first.kind!=5))throw new IllegalArgumentException("OR types");set(d,Value.scalar(first.kind,first.scalar|second.scalar));break;
  case 0xba32:d=b(0x48-u8());c=b(0x0f-u8());a=b(0x9c-u8());first=get(a);second=get(c);if(first.kind!=second.kind)throw new IllegalArgumentException("DIV types");if(first.kind==2){if(second.asDouble()==0)throw new ArithmeticException("zero floating divisor");value=Value.doubleValue(first.asDouble()/second.asDouble());}else if(first.kind==0||first.kind==5)value=Value.scalar(first.kind,first.signed()/second.signed());else throw new IllegalArgumentException("DIV kind");set(d,value);break;
  case 0x46cd:a=u8()^0x51;c=b(-u8()-0x58);d=u8()^0xe8;first=get(a);second=get(c);if(first.kind!=second.kind||(first.kind!=0&&first.kind!=4&&first.kind!=5))throw new IllegalArgumentException("AND types");set(d,Value.scalar(first.kind,first.scalar&second.scalar));break;
  case 0x1323:a=u8()^0x3b;c=b(-u8()-0x37);d=b(u8()+0x39);first=get(a);second=get(c);if(first.kind!=second.kind)throw new IllegalArgumentException("GT types");boolean greater;switch(first.kind){case 0:case 5:greater=first.signed()>second.signed();break;case 4:greater=(first.scalar^Long.MIN_VALUE)>(second.scalar^Long.MIN_VALUE);break;case 1:greater=byteCompare(first.bytes,second.bytes)>0;break;case 2:greater=first.asDouble()>second.asDouble();break;default:throw new IllegalArgumentException("GT kind");}set(d,Value.scalar(0,greater?1:0));break;
  case 0xc3ef:c=b(-u8()-0x1e);a=u8()^0xfd;if(!get(a).truth()){second=get(c);require(second,0);set(26,second);}break;
  case 0xef65:d=b(0x0c-u8());raw=newAllocation(0x60);sha1.put(raw,MessageDigest.getInstance("SHA-1"));set(d,Value.scalar(4,raw));break;
  case 0x7577:a=u8()^0xa0;c=u8()^0xd8;first=get(a);second=get(c);require(first,4);require(second,1);if(sha1.get(first.scalar)==null)throw new IllegalStateException("missing SHA1 ctx");sha1.get(first.scalar).update(second.bytes);break;
  case 0x403c:d=b(0x71-u8());a=b(u8()+0x12);first=get(a);require(first,4);if(sha1.get(first.scalar)==null)throw new IllegalStateException("missing SHA1 ctx");set(d,Value.bytes(sha1.get(first.scalar).digest()));sha1.put(first.scalar,null);set(a,Value.scalar(4,0));break;
  case 0x7bd6:d=b(u8()-0x2f);a=b(0x94-u8());first=get(a);require(first,3);set(d,Value.bytes(javaSource().byteArray(first.object)));break;
  case 0x424d:a=b(-u8()-0x27);d=u8()^0x67;first=get(a);require(first,1);set(d,Value.object(javaSource().byteArrayFrom(first.bytes)));break;
  case 0xc6f7:a=b(0x6b-u8());c=u8()^0xac;d=u8()^0x29;e=b(0x9d-u8());first=get(a);second=get(e);require(first,0);require(second,3);set(d,javaSource().arrayElement(second.object,(int)first.scalar,text(get(c))));break;
  case 0xd1e2:a=b(u8()+9);c=u8()^0xcf;d=u8()^0x28;first=get(a);require(first,3);descriptor=text(get(c));set(d,Value.object(javaSource().construct(first.object,descriptor,arguments(descriptor,0xa3186fb537abfc32L,false))));break;
  case 0x4f90:a=u8()^0xa0;d=b(u8()+0x90);set(d,get(a));if(macs.containsKey(a))macs.put(d,macs.get(a).copy());break;
  case 0x0f88:a=((int)uint(2))^0x0952;a=(short)a;c=b(u8()+0x76);d=b(u8()+0x33);first=get(c);second=get(d);require(first,4);if(first.scalar==0)throw new IllegalArgumentException("null memory base");if(second.kind==1||second.kind==3)throw new UnsupportedInstruction("Non-scalar memory-store representation not recovered");byte[] stored=Rules.little(second.scalar,second.kind==0?4:8);for(int i=0;i<stored.length;i++)memory.put(first.scalar+a+i,stored[i]);break;
  case 0x0b2d:d=u8()^0x37;a=b(-u8()-5);byte[] tag=readTag();first=get(a);second=get(d);require(second,1);byte[] append=Rules.protobuf(tag,first);if(d==16){Value currentKey=get(15);require(currentKey,5);Slot16Codec.MacState mac=macs.get(d);if(mac==null){mac=new Slot16Codec.MacState();macs.put(d,mac);}mac.update(second.bytes.length,append,currentKey.scalar);set(d,Value.bytes(Slot16Codec.appendCiphertext(second.bytes,append,currentKey.scalar)));}else set(d,Value.bytes(Rules.concat(second.bytes,append)));break;
  case 0xd3d2:a=b(-u8()-0x19);c=b(u8()-0x0e);e=u8()^0x9e;d=b(u8()-0x55);first=get(a);second=get(c);value=get(e);require(first,0);if((int)first.scalar<0)throw new IllegalArgumentException("negative byte search start");if(value.kind==4)throw new UnsupportedInstruction("Pointer substring search needs genuine native memory");require(value,1);byte[] needle;if(second.kind==0)needle=new byte[]{(byte)second.scalar};else if(second.kind==1)needle=second.bytes;else throw new IllegalArgumentException("substring needle type");int found=-1,start=(int)first.scalar;if(start<=value.bytes.length){for(int i=start;i<=value.bytes.length-needle.length;i++){boolean match=true;for(int j=0;j<needle.length;j++)if(value.bytes[i+j]!=needle[j]){match=false;break;}if(match){found=i;break;}}}set(d,Value.scalar(0,found));break;
  case 0x611e:d=u8()^0xc6;if(macs.containsKey(16)&&macs.get(16).initialized())set(d,Value.bytes(macs.get(16).digest()));break;
  case 0x6540:d=b(0x97-u8());int offset=(int)uint(4)^0xb6cfae0f;PcbcReader.Read length=reader.read(offset,2);int size=(int)(0x6628L-length.unsigned())&65535;PcbcReader.Read body=reader.read(length.next,size);byte[] encoded=body.bytes;for(int i=0;i<size;i++)encoded[i]=(byte)((0x89fb7b887edb6628L>>>(8*(i&7)))-encoded[i]);PcbcReader.Read sourceIndex=reader.read(body.next,1);a=b((int)sourceIndex.unsigned()+2);set(d,Value.bytes(Rules.decodeString(encoded,frameKey,frameFlags,a)));break;
  case 0x4e90:a=b(0x5d-u8());d=b(u8()+0x25);c=b(-u8()-0x5f);first=get(c);require(first,0);set(d,Value.object(javaSource().newArray(text(get(a)),(int)first.scalar)));break;
  case 0xa17c:a=u8()^0x5f;c=b(0x35-u8());d=b(u8()-0x4c);e=u8()^0x33;first=get(a);second=get(e);require(first,0);require(second,3);javaSource().setArrayElement(second.object,(int)first.scalar,get(d),text(get(c)));break;
  case 0xcfab:d=b(-9-u8());a=b(u8()+11);set(d,Value.object(javaSource().findClass(text(get(a)))));break;
  case 0xbe05:a=u8()^0x5d;c=b(0x25-u8());d=b(u8()-0x63);e=u8()^0xfe;first=get(c);require(first,3);set(d,javaSource().staticField(first.object,text(get(a)),text(get(e))));break;
  case 0x5c1a:d=b(u8()-1);a=b(u8()+0x42);c=u8()^0x17;e=b(u8()-0x3f);first=get(a);require(first,3);set(d,javaSource().instanceField(first.object,text(get(e)),text(get(c))));break;
  case 0x7670:a=b(0x7a-u8());d=b(-u8()-0x12);first=get(a);switch(first.kind){case 1:value=first;break;case 3:value=Value.bytes(javaSource().stringify(first.object));break;case 0:case 5:value=Value.bytes(ModifiedUtf8.encode(Long.toString(first.signed())));break;case 2:throw new UnsupportedInstruction("Native double formatting needs explicit port; do not substitute Java formatting");default:throw new UnsupportedInstruction("Native pointer C-string conversion not recovered");}set(d,value);break;
  case 0x2f0:case 0xb235:
   if(key==0x2f0){a=b(10-u8());d=b(u8()+0x15);c=b(0x2a-u8());e=b(u8()+0xb7);}else{c=b(0x4c-u8());e=b(0x12-u8());d=b(u8()+7);a=u8()^0xdd;}
   first=get(c);require(first,3);name=text(get(a));descriptor=text(get(e));Value[] args=arguments(descriptor,key==0x2f0?0x08be210a8d9f8edbL:0x9d6e464910b774a4L,key==0x2f0);value=javaSource().invoke(first.object,name,descriptor,args,key==0x2f0);if(value!=null)set(d,value);break;
  case 0xcede:a=u8()^0x4c;first=get(a);require(first,0);frames.add(new HashMap<Integer,Value>());set(26,first);break;
  case 0xbf46:a=b(u8()-0x22);byte[] exports=read(a);Map<Integer,Value> returned=new HashMap<Integer,Value>();for(int i=0;i<a;i++){d=b((exports[i]&255)+(int)(0x953d32b1752df0deL>>>(8*(i&7))));returned.put(d,get(d));}if(frames.size()==1)set(26,Value.scalar(0,program.length));else{frames.remove(frames.size()-1);for(Map.Entry<Integer,Value> item:returned.entrySet())set(item.getKey(),item.getValue());}break;
  default:throw new UnsupportedInstruction("Unsupported handler 0x"+Integer.toHexString(key)+" at PC "+before);
  }
  steps++;last=new Instruction(before,position(),opcode,index,key);return last;
 }
}

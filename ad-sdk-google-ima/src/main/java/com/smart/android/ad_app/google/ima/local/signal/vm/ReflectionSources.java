package com.smart.android.ad_app.google.ima.local.signal.vm;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Actual local Java/Android reflection implementation. Contains no SDK, native
 * execution, network transport, signal cache, or RPC client. */
public class ReflectionSources implements JavaSources {
 protected final ClassLoader loader;
 public ReflectionSources(ClassLoader loader){this.loader=loader;}
 public Object findClass(String name)throws Exception{return Class.forName(name.replace('/','.'),true,loader);}
 public static List<String> argumentDescriptors(String signature){
  if(signature.length()<3||signature.charAt(0)!='(')throw new IllegalArgumentException("method descriptor");
  List<String> result=new ArrayList<String>();int i=1;while(signature.charAt(i)!=')'){int start=i;while(signature.charAt(i)=='[')i++;if(signature.charAt(i)=='L'){i=signature.indexOf(';',i)+1;if(i==0)throw new IllegalArgumentException("unterminated descriptor");}else i++;result.add(signature.substring(start,i));}return result;
 }
 public static String returnDescriptor(String signature){int end=signature.indexOf(')');if(end<0||end+1>=signature.length())throw new IllegalArgumentException("method result descriptor");return signature.substring(end+1);}
 protected Class<?> type(String descriptor)throws Exception{
  switch(descriptor.charAt(0)){case 'V':return void.class;case 'Z':return boolean.class;case 'B':return byte.class;case 'C':return char.class;case 'S':return short.class;case 'I':return int.class;case 'J':return long.class;case 'F':return float.class;case 'D':return double.class;case '[':return (Class<?>)findClass(descriptor);case 'L':return (Class<?>)findClass(descriptor.substring(1,descriptor.endsWith(";")?descriptor.length()-1:descriptor.length()));default:throw new IllegalArgumentException("unknown type "+descriptor);}
 }
 protected Class<?>[] parameterTypes(List<String> descriptors)throws Exception{Class<?>[] result=new Class<?>[descriptors.size()];for(int i=0;i<result.length;i++)result[i]=type(descriptors.get(i));return result;}
 protected Object argument(Value value,String descriptor){
  if(value.kind==3)return value.object;if(value.kind==1)return ModifiedUtf8.decode(value.bytes);
  char tag=descriptor.charAt(0);if(value.kind==0||value.kind==5){long n=value.signed();switch(tag){case 'Z':return n!=0;case 'B':return (byte)n;case 'C':return (char)n;case 'S':return (short)n;case 'I':return (int)n;case 'J':return n;default:throw new IllegalArgumentException("scalar cannot supply reference argument");}}
  if(value.kind==2){if(tag=='F')return (float)value.asDouble();if(tag=='D')return value.asDouble();}
  throw new IllegalArgumentException("unsupported Java argument conversion");
 }
 protected Object[] arguments(Value[] values,List<String> descriptors){if(values.length!=descriptors.size())throw new IllegalArgumentException("arity");Object[] args=new Object[values.length];for(int i=0;i<args.length;i++)args[i]=argument(values[i],descriptors.get(i));return args;}
 protected Value result(Object value,String descriptor){switch(descriptor.charAt(0)){case 'V':return null;case 'L':case '[':return Value.object(value);case 'Z':return Value.scalar(0,((Boolean)value)?1:0);case 'C':return Value.scalar(0,(Character)value);case 'B':case 'S':case 'I':return Value.scalar(0,((Number)value).intValue());case 'J':return Value.scalar(5,((Number)value).longValue());case 'F':case 'D':return Value.doubleValue(((Number)value).doubleValue());default:throw new IllegalArgumentException("return descriptor");}}
 public Object newArray(String descriptor,int length)throws Exception{return Array.newInstance(type(descriptor),length);}
 public Value arrayElement(Object array,int index,String descriptor)throws Exception{return result(Array.get(array,index),descriptor);}
 public void setArrayElement(Object array,int index,Value value,String descriptor)throws Exception{Array.set(array,index,argument(value,descriptor));}
 public Object construct(Object owner,String signature,Value[] values)throws Exception{if(!returnDescriptor(signature).equals("V"))throw new IllegalArgumentException("constructor return");List<String> types=argumentDescriptors(signature);Constructor<?> c=((Class<?>)owner).getDeclaredConstructor(parameterTypes(types));c.setAccessible(true);return c.newInstance(arguments(values,types));}
 protected Field field(Class<?> owner,String name)throws Exception{Class<?> current=owner;while(current!=null){try{Field f=current.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException missing){current=current.getSuperclass();}}throw new NoSuchFieldException(owner.getName()+"."+name);}
 public Value staticField(Object owner,String name,String descriptor)throws Exception{Field f=field((Class<?>)owner,name);if(!Modifier.isStatic(f.getModifiers())||!f.getType().equals(type(descriptor)))throw new NoSuchFieldException("field type/static contract");return result(com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.readField(f,null),descriptor);}
 public Value instanceField(Object receiver,String name,String descriptor)throws Exception{Field f=field(receiver.getClass(),name);if(Modifier.isStatic(f.getModifiers())||!f.getType().equals(type(descriptor)))throw new NoSuchFieldException("field type/instance contract");return result(com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.readField(f,receiver),descriptor);}
 protected Method method(Class<?> owner,String name,Class<?>[] types)throws Exception{
  Class<?> current=owner;while(current!=null){try{Method m=current.getDeclaredMethod(name,types);m.setAccessible(true);return m;}catch(NoSuchMethodException missing){current=current.getSuperclass();}}
  Method method=owner.getMethod(name,types);method.setAccessible(true);return method;
 }
 public Value invoke(Object receiver,String name,String signature,Value[] values,boolean isStatic)throws Exception{List<String> types=argumentDescriptors(signature);Class<?> owner=isStatic?(Class<?>)receiver:receiver.getClass();Method m=method(owner,name,parameterTypes(types));if(Modifier.isStatic(m.getModifiers())!=isStatic||!m.getReturnType().equals(type(returnDescriptor(signature))))throw new NoSuchMethodException("method result/static contract");return result(com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.invoke(m,isStatic?null:receiver,arguments(values,types)),returnDescriptor(signature));}
 public byte[] byteArray(Object array){if(!(array instanceof byte[]))throw new IllegalArgumentException("byte[] required");return ((byte[])array).clone();}
 public Object byteArrayFrom(byte[] bytes){return bytes.clone();}
 public byte[] stringify(Object value){String text=value==null?"null":value.toString();if(text==null)throw new IllegalStateException("Java toString returned null; native exception dispatch not yet recovered");return ModifiedUtf8.encode(text);}
}

package com.smart.android.ad_app.google.ima.local.signal.vm;

/** Local platform primitive bridge. Production implementations use real Java
 * objects and direct reflection, never RPC or completed signal outputs. */
public interface JavaSources {
 Object findClass(String name)throws Exception;
 Object newArray(String componentDescriptor,int length)throws Exception;
 Value arrayElement(Object array,int index,String descriptor)throws Exception;
 void setArrayElement(Object array,int index,Value value,String descriptor)throws Exception;
 Object construct(Object owner,String signature,Value[] arguments)throws Exception;
 Value staticField(Object owner,String name,String descriptor)throws Exception;
 Value instanceField(Object receiver,String name,String descriptor)throws Exception;
 Value invoke(Object receiver,String name,String signature,Value[] arguments,boolean isStatic)throws Exception;
 byte[] byteArray(Object array)throws Exception;
 Object byteArrayFrom(byte[] bytes)throws Exception;
 byte[] stringify(Object object)throws Exception;
}

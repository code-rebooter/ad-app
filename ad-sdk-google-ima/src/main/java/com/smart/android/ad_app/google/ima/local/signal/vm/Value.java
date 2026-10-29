package com.smart.android.ad_app.google.ima.local.signal.vm;

/** Logical VM slot, with raw IEEE754 bits for kind2 and real objects for kind3. */
public final class Value {
    public final int kind;public final long scalar;public final byte[] bytes;public final Object object;
    private Value(int kind,long scalar,byte[] bytes,Object object){this.kind=kind;this.scalar=kind==0?scalar&0xffffffffL:scalar;this.bytes=bytes==null?null:bytes.clone();this.object=object;}
    public static Value scalar(int kind,long value){if(kind!=0&&kind!=2&&kind!=4&&kind!=5)throw new IllegalArgumentException("scalar kind");return new Value(kind,value,null,null);}
    public static Value bytes(byte[] value){return new Value(1,0,value,null);}
    public static Value object(Object value){return new Value(3,0,null,value);}
    public static Value doubleValue(double value){return scalar(2,Double.doubleToRawLongBits(value));}
    public double asDouble(){if(kind!=2)throw new IllegalStateException("double slot required");return Double.longBitsToDouble(scalar);}
    public long signed(){return kind==0?(int)scalar:scalar;}
    public boolean truth(){switch(kind){case 0:case 4:case 5:return scalar!=0;case 2:return (scalar&0x7fffffffffffffffL)!=0;case 1:return bytes.length!=0;case 3:return object!=null;default:throw new IllegalStateException("undefined slot kind");}}
}

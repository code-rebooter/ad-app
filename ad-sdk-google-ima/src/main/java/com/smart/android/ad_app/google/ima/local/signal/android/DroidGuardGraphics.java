package com.smart.android.ad_app.google.ima.local.signal.android;

import android.opengl.GLES20;
import java.nio.ByteBuffer;
import java.nio.Buffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/** Independent real EGL/GLES source; shaders and arithmetic are pinned rules. */
final class DroidGuardGraphics {
    private static final String VERTEX="precision mediump float;attribute vec4 aPosition;attribute vec4 aColor;varying vec4 vColor;void main(void) {    gl_Position = aPosition;    vColor = aColor;}";
    private static final String[] FRAGMENTS={"precision mediump float;varying vec4 vColor;void main() {    gl_FragColor = vColor * 0.9;}","precision mediump float;varying vec4 vColor;void main() {    gl_FragColor = abs(cos((vColor        * vec4(100.0, 1000.0, 10000.0, gl_FragCoord.x*gl_FragCoord.y))));}"};
    private static final float[] COLORS={0f,.1f,0f,0f,.5f,.5f,.5f,.4f,.9f,.9f,.8f,.9f};
    static final class GlFailure extends Exception{GlFailure(String message){super(message);}}
    private static void check(String operation)throws GlFailure{int error=GLES20.glGetError();if(error!=0)throw new GlFailure(operation+": "+error);}
    private static int shader(int kind,String source)throws GlFailure {
        int result=GLES20.glCreateShader(kind);if(result==0)throw new GlFailure("glCreateShader: "+GLES20.glGetError());
        GLES20.glShaderSource(result,source);check("glShaderSource");GLES20.glCompileShader(result);check("glCompileShader");
        int[] compiled={0};GLES20.glGetShaderiv(result,35713,compiled,0);check("glGetShaderiv");if(compiled[0]!=1)throw new GlFailure("Shader compilation failed. "+GLES20.glGetShaderInfoLog(result));check("glCreateShader");return result;
    }
    private static int attribute(int program,String name)throws GlFailure{int result=GLES20.glGetAttribLocation(program,name);check("glGetAttribLocation");if(result<0)throw new GlFailure("glGetAttribLocation: "+GLES20.glGetError());return result;}
    private static long pixelHash(ByteBuffer pixels)throws Exception{((Buffer)pixels).position(0);MessageDigest digest=MessageDigest.getInstance("SHA-1");digest.update(pixels);((Buffer)pixels).position(0);byte[] bytes=digest.digest();long value=0;for(int i=0;i<8;i++)value=(value<<8)|(bytes[i]&255);return value;}
    static DroidGuardHelperState.GraphicsResult capture(float[] inputs)throws Exception {
        EGL10 egl=(EGL10)EGLContext.getEGL();EGLDisplay display=null;EGLContext context=null;EGLSurface surface=null;
        int vertex=-1;int[] programs={-1,-1},fragments={-1,-1},positions={-1,-1},colors={-1,-1};
        try {
            display=egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);if(EGL10.EGL_NO_DISPLAY.equals(display))throw new GlFailure("eglGetDisplay: "+egl.eglGetError());
            if(!egl.eglInitialize(display,new int[2]))throw new GlFailure("eglInitialize: "+egl.eglGetError());
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];int[] attrs={12324,8,12323,8,12322,8,12321,8,12339,1,12352,4,12344};
            if(!egl.eglChooseConfig(display,attrs,configs,1,count)||count[0]==0)throw new GlFailure("eglChooseConfig: "+egl.eglGetError());
            context=egl.eglCreateContext(display,configs[0],EGL10.EGL_NO_CONTEXT,new int[]{12440,2,12344});if(EGL10.EGL_NO_CONTEXT.equals(context))throw new GlFailure("eglCreateContext: "+egl.eglGetError());
            surface=egl.eglCreatePbufferSurface(display,configs[0],new int[]{12375,32,12374,32,12344});if(EGL10.EGL_NO_SURFACE.equals(surface))throw new GlFailure("eglCreatePbufferSurface: "+egl.eglGetError());
            if(!egl.eglMakeCurrent(display,surface,surface,context))throw new GlFailure("eglMakeCurrent: "+egl.eglGetError());
            String vendor=GLES20.glGetString(7936);check("glGetString");String renderer=GLES20.glGetString(7937);check("glGetString");String version=GLES20.glGetString(7938);check("glGetString");
            GLES20.glClearColor(0f,0f,0f,1f);check("glClearColor");GLES20.glDisable(2929);check("glDisable");GLES20.glDisable(3042);check("glDisable");vertex=shader(35633,VERTEX);
            for(int n=0;n<2;n++) {
                fragments[n]=shader(35632,FRAGMENTS[n]);programs[n]=GLES20.glCreateProgram();check("glCreateProgram");if(programs[n]==0)throw new GlFailure("glCreateProgram: "+GLES20.glGetError());
                GLES20.glAttachShader(programs[n],vertex);check("glAttachShader");GLES20.glAttachShader(programs[n],fragments[n]);check("glAttachShader");GLES20.glLinkProgram(programs[n]);check("glLinkProgram");
                int[] linked={0};GLES20.glGetProgramiv(programs[n],35714,linked,0);check("glGetProgramiv");if(linked[0]!=1)throw new GlFailure("Shader consolidation failed.");
                positions[n]=attribute(programs[n],"aPosition");colors[n]=attribute(programs[n],"aColor");GLES20.glEnableVertexAttribArray(positions[n]);check("glEnableVertexAttribArray");GLES20.glEnableVertexAttribArray(colors[n]);check("glEnableVertexAttribArray");
            }
            FloatBuffer positionsBuffer=ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).asFloatBuffer();FloatBuffer colorsBuffer=ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).asFloatBuffer();ByteBuffer[] pixels=new ByteBuffer[2];
            GLES20.glClear(16384);check("glClear");
            for(int n=0;n<2;n++) {
                ((Buffer)positionsBuffer).clear();((Buffer)colorsBuffer).clear();float extent=n==0?1000f:10f;float[] coordinates={-2f,-2f,0f,1f,extent,-2f,0f,1f,-2f,extent,0f,1f};
                GLES20.glVertexAttribPointer(positions[n],4,5126,false,16,positionsBuffer);check("glVertexAttribPointer");GLES20.glVertexAttribPointer(colors[n],4,5126,false,16,colorsBuffer);check("glVertexAttribPointer");GLES20.glUseProgram(programs[n]);check("glUseProgram");
                for(int i=0;i<12;i++)positionsBuffer.put(coordinates[i]);
                for(float value:inputs)if(value<-.01d||value>1.01d)throw new GlFailure("Floats in the parameter are out of the range [0, 1].");
                for(int i=0;i<12;i++)colorsBuffer.put(COLORS[i]+inputs[i]*.1f);
                ((Buffer)positionsBuffer).clear();((Buffer)colorsBuffer).clear();GLES20.glDrawArrays(4,0,positionsBuffer.remaining()/4);check("glDrawArrays");
                pixels[n]=ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());((Buffer)pixels[n]).clear();GLES20.glReadPixels(0,0,32,32,6408,5121,pixels[n]);check("glReadPixels");((Buffer)pixels[n]).clear();
            }
            return new DroidGuardHelperState.GraphicsResult(pixelHash(pixels[0]),pixelHash(pixels[1]),vendor+':'+renderer,version);
        } finally {
            for(int n=0;n<2;n++){if(colors[n]!=-1){GLES20.glDisableVertexAttribArray(colors[n]);GLES20.glGetError();}if(positions[n]!=-1){GLES20.glDisableVertexAttribArray(positions[n]);GLES20.glGetError();}if(programs[n]!=-1){GLES20.glDeleteProgram(programs[n]);GLES20.glGetError();}if(fragments[n]!=-1){GLES20.glDeleteShader(fragments[n]);GLES20.glGetError();}}
            if(vertex!=-1){GLES20.glDeleteShader(vertex);GLES20.glGetError();}
            if(surface!=null){egl.eglDestroySurface(display,surface);egl.eglMakeCurrent(display,EGL10.EGL_NO_SURFACE,EGL10.EGL_NO_SURFACE,EGL10.EGL_NO_CONTEXT);}
            if(context!=null)egl.eglDestroyContext(display,context);if(display!=null)egl.eglTerminate(display);
        }
    }
}

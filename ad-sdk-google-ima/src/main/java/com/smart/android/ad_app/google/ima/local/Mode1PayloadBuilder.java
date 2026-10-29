package com.smart.android.ad_app.google.ima.local;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import com.smart.android.ad_app.google.ima.local.portable.Base64Compat;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeMap;

/**
 * Offline source-domain mode1 payload prototype for the pinned IMA3.35.1 compat
 * module. No Android, IMA DTO, device access, captured signal or implicit clock /
 * random / file / network source. This creates the protobuf payload only.
 *
 * Sources must be the actual collection results for the invocation being modeled.
 * Outer module selection, async cache/future races, GASS, task cancellation and
 * flags are explicit caller inputs. This is not a complete ms SDK replacement.
 */
public final class Mode1PayloadBuilder {
    public static final String PINNED_MODULE_VERSION = "1.460000000";
    public enum Task {
        APP_INFO, WALL_CLOCK, OS_RELEASE, DISPLAY, TIME_ZONE, SIGNATURE, BATTERY,
        ORIENTATION, PROCESS, PROCESSORS, BUILD_CHECK, USB, ADB, CLOCKS,
        SCREEN_FLAGS, NETWORK, PROXY, LAUNCHER, MODULE_VERSION
    }
    /** SUCCESS means the actual task body completed its supported handling.
     * RESULT_AVAILABLE explicitly models a null/partial decoded return that can
     * fail during handling; it is not equivalent to successful task completion. */
    public enum Stage {
        NOT_ENTERED, ENTERED_NO_RESULT, SUCCESS, RESULT_AVAILABLE,
        INVOCATION_TARGET_EXCEPTION, ILLEGAL_ACCESS_EXCEPTION, SETTING_NOT_FOUND
    }
    /** Null raw extras mean missing extras, using the module's documented defaults. */
    public static final class Battery {
        public Integer status, level, scale, temperature;
    }
    public static final class ProcessInfo {
        public final int pid, importance;
        public final String processName;
        public ProcessInfo(int pid, String processName, int importance) {
            this.pid = pid; this.processName = processName; this.importance = importance;
        }
    }
    public static final class AppInfo {
        public boolean packageInfoAvailable;
        public Long versionCode;
        public String versionName;
        /** False models an exception before the module assigns package/installer. */
        public boolean installerReadSucceeded;
        public String installerPackageName;
    }
    /**
     * Module n input plus explicit outer async primary override. Caller resolves
     * checksum/GASS/helper branch outcomes and cache snapshot without guessing.
     */
    public static final class AppDigestInput {
        public String suppliedDigest;
        public boolean computeApkDigest;
        /** Actual SHA-256 digest of APK file when collection succeeded; not APK bytes. */
        public byte[] apkSha256Bytes;
        public boolean overridePrimary;
        public String resolvedPrimary;
    }
    public static final class Inputs {
        public String sdkVersion, osRelease, packageName, buildModel, buildHardware, httpProxyHost;
        public Long wallTimeMillis, baselineSeconds, elapsedRealtimeMillis, uptimeMillis;
        public Long networkTiming0, networkTiming1;
        public Integer androidSdk, widthPixels, heightPixels, zoneOffsetMillis, dstOffsetMillis;
        public Integer availableProcessors, orientation, ownPid, processRandomDraw;
        public Boolean superuserApkExists, usbConnected, adbEnabled, screenOn, keyguardLocked;
        /** Actual zzjj.zzs() construction-time result. */
        public Boolean screenRuntimeFlag;
        public Boolean launcherAvailable, networkVpn, networkWifi, networkCellular;
        /** Null means unsupported/unspecified; false models a real null capabilities object. */
        public Boolean networkCapabilitiesPresent;
        /** Use exactly one actual source: certificate bytes OR method SHA1 hex output. */
        public byte[] signingCertificateBytes;
        public String signatureSha1Hex;
        public Battery battery;
        /** Alternative actual module method result, mutually exclusive with battery. */
        public int[] batteryResult;
        public List<ProcessInfo> processes;
        public AppInfo appInfo;
        public AppDigestInput appDigest;
        public boolean executorAvailable = true, moduleReady = true;
        public boolean enableNetwork, enableProxy, enableModuleVersion;
        public boolean processMatchByPid, networkProviderPresent;
        public final EnumMap<Task, Stage> stages = new EnumMap<Task, Stage>(Task.class);
        public Inputs stage(Task task, Stage stage) { stages.put(task, stage); return this; }
        public Stage stage(Task task) {
            Stage stage = stages.get(task); return stage == null ? Stage.NOT_ENTERED : stage;
        }
    }
    public static final class Result {
        public final byte[] payload;
        public final List<Integer> fieldNumbers;
        public final List<String> notes;
        private Result(byte[] payload, List<Integer> fields, List<String> notes) {
            this.payload = payload;
            this.fieldNumbers = Collections.unmodifiableList(fields);
            this.notes = Collections.unmodifiableList(notes);
        }
    }
    private Mode1PayloadBuilder() {}

    public static byte[] build(Inputs in) { return buildDetailed(in).payload; }
    public static Result buildDetailed(Inputs in) {
        if (in == null) throw new IllegalArgumentException("inputs required");
        Writer out = new Writer(); List<String> notes = new ArrayList<String>();
        if (in.sdkVersion != null && !in.sdkVersion.isEmpty()) out.string(2, in.sdkVersion);
        if (!in.executorAvailable) {
            notes.add("mode1 executor absent: no task scheduling"); return finish(out, notes);
        }
        if (!in.moduleReady) {
            out.integer(20, 16384); notes.add("module unavailable fallback"); return finish(out, notes);
        }
        for (Task task : Task.values()) {
            if (!enabled(in, task)) continue;
            Stage stage = in.stage(task);
            if (stage == Stage.NOT_ENTERED) { notes.add(task + ": not entered / no method / cancelled before body"); continue; }
            // Prewrites occur inside task bodies, after reflection method lookup.
            switch (task) {
                case OS_RELEASE: out.string(1, "E"); break;
                case DISPLAY: out.integer(31,-1); out.integer(32,-1); break;
                case SIGNATURE: out.string(29,"E"); break;
                case BATTERY: out.integer(5,-1); out.integer(6,-1); break;
                case ORIENTATION: out.integer(12,-1); break;
                case USB: out.integer(48,2); break;
                case ADB: out.integer(49,2); break;
                default: break;
            }
            if (stage != Stage.SUCCESS && stage != Stage.RESULT_AVAILABLE) {
                if (task == Task.PROXY && stage == Stage.INVOCATION_TARGET_EXCEPTION) out.integer(73,2);
                notes.add(task + ": " + stage + "; only actual prewrites retained");
                continue;
            }
            switch (task) {
                case APP_INFO: writeApp(in,out); break;
                case WALL_CLOCK:
                    long seconds = need(in.wallTimeMillis,"wallTimeMillis") / 1000L;
                    out.integer(25,seconds);
                    long baseline = need(in.baselineSeconds,"baselineSeconds");
                    if (baseline != 0) { out.integer(17,seconds-baseline); out.integer(23,baseline); }
                    break;
                case OS_RELEASE: out.string(1,need(in.osRelease,"osRelease")); break;
                case DISPLAY:
                    if (in.widthPixels != null && in.heightPixels != null) {
                        out.integer(31,in.widthPixels); out.integer(32,in.heightPixels);
                    } else {
                        if(stage==Stage.SUCCESS)throw new IllegalArgumentException("DISPLAY SUCCESS requires valid two-item result");
                        notes.add("DISPLAY: null/invalid method List preserves -1 sentinels");
                    }
                    break;
                case TIME_ZONE:
                    // int addition and Java truncating division match Calendar module.
                    int zone = need(in.zoneOffsetMillis,"zoneOffsetMillis");
                    int dst = need(in.dstOffsetMillis,"dstOffsetMillis");
                    out.integer(33,(zone+dst)/60000 + 1440); break;
                case SIGNATURE:
                    if (in.signingCertificateBytes != null && in.signatureSha1Hex != null)
                        throw new IllegalArgumentException("certificate bytes and SHA1 hex are mutually exclusive");
                    String hex = in.signatureSha1Hex;
                    if (in.signingCertificateBytes != null) hex = hex(digest("SHA-1",in.signingCertificateBytes));
                    if (hex == null) {
                        if(stage==Stage.SUCCESS)throw new IllegalArgumentException("SIGNATURE SUCCESS requires nonnull method output");
                        notes.add("SIGNATURE: null method result throws before replacing E"); break;
                    }
                    out.string(29,Base64Compat.getUrlEncoder().withoutPadding().encodeToString(hex.getBytes(StandardCharsets.UTF_8)));
                    break;
                case BATTERY: writeBattery(in,out); break;
                case ORIENTATION:
                    int orientation = need(in.orientation,"orientation");
                    out.integer(12,orientation==1?1:orientation==2?3:orientation==3?20:-1); break;
                case PROCESS: writeProcess(in,out); break;
                case PROCESSORS: out.integer(44,need(in.availableProcessors,"availableProcessors")); break;
                case BUILD_CHECK:
                    int code = need(in.superuserApkExists,"superuserApkExists") ? 1 : 0;
                    String model=need(in.buildModel,"buildModel"), hardware=need(in.buildHardware,"buildHardware");
                    if ("google_sdk".equals(model)||"Emulator".equals(model)||"sdk".equals(model)||"goldfish".equals(hardware)||"ranchu".equals(hardware)) code=2;
                    out.integer(22,code); break;
                case USB: out.integer(48,need(in.usbConnected,"usbConnected")?1:0); break;
                case ADB: out.integer(49,need(in.adbEnabled,"adbEnabled")?1:0); break;
                case CLOCKS:
                    // Nullable parsed helper keys are not generic optional fields: first
                    // null causes longValue NPE before either write; second null can
                    // leave only the first write, matching outer statement order.
                    if (in.elapsedRealtimeMillis==null) {
                        if(stage==Stage.SUCCESS)throw new IllegalArgumentException("CLOCKS SUCCESS requires key0");
                        notes.add("CLOCKS: missing parsed key0; no writes"); break;
                    }
                    out.integer(51,in.elapsedRealtimeMillis);
                    if (in.uptimeMillis==null) {
                        if(stage==Stage.SUCCESS)throw new IllegalArgumentException("CLOCKS SUCCESS requires key1");
                        notes.add("CLOCKS: missing parsed key1; first write retained"); break;
                    }
                    out.integer(52,in.uptimeMillis); break;
                case SCREEN_FLAGS:
                    int flags = need(in.screenOn,"screenOn")?0:2;
                    if (need(in.keyguardLocked,"keyguardLocked")) flags|=1;
                    if (!need(in.screenRuntimeFlag,"screenRuntimeFlag")) flags|=4;
                    out.integer(61,flags); break;
                case NETWORK:
                    if (!in.networkProviderPresent) { notes.add("NETWORK: provider null; body writes nothing"); break; }
                    int transport = !need(in.networkCapabilitiesPresent,"networkCapabilitiesPresent") ? -1 : need(in.networkVpn,"networkVpn")?2:need(in.networkWifi,"networkWifi")?1:need(in.networkCellular,"networkCellular")?0:-1;
                    out.integer(11,transport);
                    // These are the true caller-supplied zzjs outputs; null represents
                    // unavailable source (-1), not permission to invent timestamps.
                    long t0=in.networkTiming0==null?-1:in.networkTiming0;
                    long t1=in.networkTiming1==null?-1:in.networkTiming1;
                    if(t0>=0)out.integer(83,t0);if(t1>=0)out.integer(84,t1);break;
                case PROXY: out.integer(73,in.httpProxyHost!=null&&!in.httpProxyHost.isEmpty()?1:0); break;
                case LAUNCHER: out.integer(76,need(in.launcherAvailable,"launcherAvailable")?1:0); break;
                case MODULE_VERSION: out.string(89,PINNED_MODULE_VERSION); break;
                default: throw new AssertionError(task);
            }
        }
        return finish(out,notes);
    }
    private static boolean enabled(Inputs in,Task task) {
        if(task==Task.NETWORK)return in.enableNetwork && need(in.androidSdk,"androidSdk")>=24;
        if(task==Task.PROXY)return in.enableProxy;
        if(task==Task.MODULE_VERSION)return in.enableModuleVersion;
        return true;
    }
    private static void writeApp(Inputs in,Writer out) {
        AppInfo app=need(in.appInfo,"appInfo"); AppDigestInput d=need(in.appDigest,"appDigest");
        String primary="E";
        if(d.suppliedDigest!=null&&!d.suppliedDigest.isEmpty())primary=d.suppliedDigest;
        else if(d.computeApkDigest&&d.apkSha256Bytes!=null) {
            if(d.apkSha256Bytes.length!=32)throw new IllegalArgumentException("APK SHA256 must contain32 bytes");
            primary=hex(d.apkSha256Bytes);
        }
        if(d.overridePrimary)primary=need(d.resolvedPrimary,"resolvedPrimary");
        long version=-1;String versionName="E",pkg="E",installer="E";
        if(app.packageInfoAvailable) { version=need(app.versionCode,"versionCode");versionName=app.versionName==null?"":app.versionName; }
        if(app.installerReadSucceeded) { pkg=need(in.packageName,"packageName");installer=app.installerPackageName==null?"unknown":app.installerPackageName; }
        out.string(27,primary);out.integer(35,version);out.string(34,versionName);out.string(46,pkg);out.string(47,installer);
    }
    private static void writeBattery(Inputs in,Writer out) {
        if(in.battery!=null&&in.batteryResult!=null)throw new IllegalArgumentException("battery raw/result inputs are mutually exclusive");
        if(in.batteryResult!=null) {
            if(in.batteryResult.length<3)throw new IllegalArgumentException("module battery result requires3 items");
            out.integer(5,in.batteryResult[0]);out.integer(6,in.batteryResult[1]);
            if(in.batteryResult[2]!=Integer.MIN_VALUE)out.integer(59,in.batteryResult[2]);return;
        }
        Battery b=need(in.battery,"battery");int rawStatus=b.status==null?1:b.status;
        int status=rawStatus==2?2:rawStatus==3||rawStatus==4?1:rawStatus==5?3:0;
        int level=b.level==null?-1:b.level,scale=b.scale==null?-1:b.scale;
        int percent=level>=0&&scale>0?(int)Math.round(((double)level/(double)scale)*100.0d):-1;
        out.integer(5,status);out.integer(6,percent);
        int temp=b.temperature==null?Integer.MIN_VALUE:b.temperature;
        if(temp!=Integer.MIN_VALUE)out.integer(59,temp);
    }
    private static void writeProcess(Inputs in,Writer out) {
        int draw=need(in.processRandomDraw,"processRandomDraw");
        if(draw<1||draw>64)throw new IllegalArgumentException("processRandomDraw must be actual nextInt64+1 in1..64");
        int importance=-1;boolean foreground=false;
        if(in.processes!=null)for(ProcessInfo p:in.processes) {
            boolean match=in.processMatchByPid?p.pid==need(in.ownPid,"ownPid"):need(in.packageName,"packageName").equals(p.processName);
            if(match) { importance=p.importance;foreground=importance==100||importance==200;break; }
        }
        out.integer(3,foreground?draw|16:draw&-17);out.integer(60,importance);
    }
    private static <T>T need(T value,String name) {
        if(value==null)throw new IllegalArgumentException("successful source missing: "+name);return value;
    }
    private static byte[] digest(String algorithm,byte[] bytes) {
        try{return MessageDigest.getInstance(algorithm).digest(bytes);}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String hex(byte[] bytes) {
        char[] out=new char[bytes.length*2];char[] alphabet="0123456789abcdef".toCharArray();
        for(int i=0;i<bytes.length;i++){int b=bytes[i]&255;out[i*2]=alphabet[b>>>4];out[i*2+1]=alphabet[b&15];}return new String(out);
    }
    private static Result finish(Writer out,List<String> notes) {
        return new Result(out.bytes(),new ArrayList<Integer>(out.fields.keySet()),new ArrayList<String>(notes));
    }
    /** Minimal deterministic protobuf wire writer. No generated-message dependency. */
    private static final class Writer {
        final TreeMap<Integer,byte[]> fields=new TreeMap<Integer,byte[]>();
        void integer(int number,long value) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();varint(out,(long)number<<3);varint(out,value);fields.put(number,out.toByteArray());
        }
        void string(int number,String value) {
            byte[] b=value.getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream out=new ByteArrayOutputStream();varint(out,((long)number<<3)|2);varint(out,b.length);out.write(b,0,b.length);fields.put(number,out.toByteArray());
        }
        byte[] bytes() {ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] b:fields.values())out.write(b,0,b.length);return out.toByteArray();}
        static void varint(ByteArrayOutputStream out,long value) {
            while((value&~0x7fL)!=0){out.write(((int)value&0x7f)|0x80);value>>>=7;}out.write((int)value);
        }
    }
}

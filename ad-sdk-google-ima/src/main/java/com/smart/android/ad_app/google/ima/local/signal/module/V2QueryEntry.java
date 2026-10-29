package com.smart.android.ad_app.google.ima.local.signal.module;

/** Pinned zzhx.zzf ordering. An uninitialized process backend yields the original
 * empty string at the owner boundary, while a chosen entry remains V2 so a later
 * refresh can make the same entry ready. No readiness wait is added here. */
public final class V2QueryEntry {
 private V2QueryEntry(){}
 public interface Backend<T> {T query()throws Exception;}
 public interface Runtime<T> {void beforeQuery();void maybeRefresh();Backend<T> activeBackend();}
 public static final class Outcome<T> {
  public final boolean backendPresent;public final T value;
  private Outcome(boolean present,T value){backendPresent=present;this.value=value;}
 }
 public static <T> Outcome<T> query(Runtime<T> actualRuntime)throws Exception {
  actualRuntime.beforeQuery();actualRuntime.maybeRefresh();Backend<T> backend=actualRuntime.activeBackend();
  return backend==null?new Outcome<T>(false,null):new Outcome<T>(true,backend.query());
 }
}

package com.kineai.local.engine;
public class NativeEngine {
  public interface TokenSink { boolean onToken(String piece); }
  public static native String nativeVersion();
  public static native boolean nativeSetThreads(int n);
  public static native String nativeCpuFeatures();
  public static native String nativeInspect(String path);
  public static native long nativeLoad(String path, int ctx);
  public static native void nativeUnload(long h);
  public static native String nativeModelInfo(long h);
  public static native int nativeCountTokens(long h, String t);
  public static native String nativeChat(long h, String messagesJson, int maxTokens, float temp, long seed, TokenSink sink);
  public static void main(String[] a) throws Exception {
    System.load(a[0]);
    System.out.println("version: " + nativeVersion());
    System.out.println("threads set: " + nativeSetThreads(4));
    System.out.println("cpu: " + nativeCpuFeatures());
    System.out.println("inspect: " + nativeInspect(a[1]));
    System.out.println("inspect(bad): " + nativeInspect("/nonexistent.gguf"));
    try { nativeLoad("/nonexistent.gguf", 0); System.out.println("FAIL no throw"); } catch (RuntimeException e) { System.out.println("load(bad) threw: " + e.getMessage()); }
    long h = nativeLoad(a[1], 2048);
    System.out.println("info: " + nativeModelInfo(h) + " tokens(hello world)=" + nativeCountTokens(h, "hello world"));
    StringBuilder sb = new StringBuilder();
    String r = nativeChat(h, "[{\"role\":\"user\",\"content\":\"Say hi in one short sentence.\"}]", 40, 0f, 1, p -> { sb.append(p); return true; });
    System.out.println("answer: " + sb + "\nstats: " + r);
    int[] n = {0};
    String c = nativeChat(h, "[{\"role\":\"user\",\"content\":\"Count from 1 to 100.\"}]", 200, 0f, 1, p -> { return ++n[0] < 5; });
    System.out.println("cancel after " + n[0] + " pieces; stats: " + c);
    String again = nativeChat(h, "[{\"role\":\"user\",\"content\":\"Say ok.\"}]", 10, 0f, 1, p -> true);
    System.out.println("after cancel still works: " + again);
    nativeUnload(h);
    try { nativeModelInfo(h); } catch (Throwable t) { System.out.println("post-unload use: " + t); }
  }
}

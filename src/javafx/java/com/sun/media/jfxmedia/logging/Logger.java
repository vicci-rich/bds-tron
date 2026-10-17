package com.sun.media.jfxmedia.logging;

/** Shim of the JavaFX media logging class for JDK8 test builds (import compatibility only). */
public class Logger {
    public static final int WARNING  = 1;
    public static final int ERROR    = 2;
    public static final int DEBUG    = 3;
    public static final int INFO     = 4;
    public static final int CANLOG   = 5;

    public static boolean canLog(int level) { return true; }
    public static void logMsg(int level, String msg) { }
    public static void message(String msg) { System.out.println(msg); }
    public static void logMsg(int level, String msg1, String msg2) { }
}

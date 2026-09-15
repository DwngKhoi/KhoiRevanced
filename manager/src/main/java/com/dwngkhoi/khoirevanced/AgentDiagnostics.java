package com.dwngkhoi.khoirevanced;

final class AgentDiagnostics {
    static { System.loadLibrary("khoirevanced_agent"); }
    private AgentDiagnostics() { }
    static native String nativeAgentVersion();
    static native boolean nativeWriteMarker(String path);
}
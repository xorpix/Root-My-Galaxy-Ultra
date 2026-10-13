package dev.experimental.rmgu.bootprobe;

final class NativeProperties {
    static { System.loadLibrary("bootprobe"); }
    private NativeProperties() {}
    static native String[] read();
}

package dev.busung.s25uroot

object NativeProbe {
    init {
        System.loadLibrary("s25u_native")
    }

    external fun isKernelSuActive(): Boolean
}

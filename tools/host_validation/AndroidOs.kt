package android.os
object Process { fun myUid() = 10345 }
object SystemClock { fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 }

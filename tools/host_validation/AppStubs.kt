package dev.busung.s25uroot
// Test-only Android/transport scaffolding. All process responses are simulated.
internal enum class KernelSuFlavor(val id: String) { KernelSu("kernelsu"), KernelSuNext("kernelsu-next"), ReSukiSU("resukisu"); val label: String get() = id }
internal data class RemoteArtifact(val size: Long, val sha256: String?)
internal data class TargetProfile(val flavor: KernelSuFlavor, val kernelSu: RemoteArtifact)
internal data class VerifiedPayloads(val profile: TargetProfile)
internal enum class RunStage { Exploit, KernelSu, Verify }
internal object RootStatusProbe { var active = false; fun isActive() = active }
internal object AppPreferences {
    // These backend tests leave optional partition protection and module wipe off.
    fun partitionReadOnlyMode(context: android.content.Context) = false
    fun wipeModuleState(context: android.content.Context) = false
    fun setReadOnlyProtectedDevices(context: android.content.Context, bootToken: String?, devices: Int): Unit =
        error("Partition protection is outside this fixture")
}
internal object ShizukuController {
    lateinit var handler: (Array<String>, Array<String>?, String?) -> Process
    fun exec(argv: Array<String>, env: Array<String>? = null, dir: String? = null) = handler(argv, env, dir)
}

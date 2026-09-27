@file:OptIn(org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class)
@file:Suppress("DEPRECATION_ERROR", "OPT_IN_USAGE_ERROR")

package dev.busung.s25uroot

import java.io.File
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtPsiFactory

/** Syntax check only; Android/Compose type checking remains a local Gradle task. */
fun parseProjectSources(project: File) {
    val disposable = Disposer.newDisposable()
    try {
        val environment = KotlinCoreEnvironment.createForProduction(
            disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        val factory = KtPsiFactory(environment.project, false)
        var checked = 0
        File(project, "app/src").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val parsed = factory.createFile(file.name, file.readText())
            val errors = PsiTreeUtil.collectElementsOfType(parsed, PsiErrorElement::class.java)
            check(errors.isEmpty()) { "${file.path}: ${errors.joinToString { it.errorDescription }}" }
            checked++
        }
        println("PASS Kotlin syntax: $checked production/test source files (not Android type checking)")
    } finally {
        Disposer.dispose(disposable)
    }
}

package com.namanbansal.forge.guard

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.namanbansal.forge.ForgeApp
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The default ViewModel factory reflects for a public (), (Application), (SavedStateHandle) or (Application, SavedStateHandle)
 * constructor. A Kotlin default argument does not generate one of those, which crashed the released currency converter.
 * Every concrete ViewModel in the app must have one, unless it is created by an explicit factory listed in [FACTORY_CREATED].
 */
class ViewModelConstructorTest {
    private val factoryCreated = FACTORY_CREATED

    private fun appClasses(): List<Class<*>> {
        val root = File(ForgeApp::class.java.protectionDomain.codeSource.location.toURI())
        val names = if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }.toList()
        } else {
            java.util.zip.ZipFile(root).use { z -> z.entries().toList().map { it.name }.filter { it.endsWith(".class") }.map { it.removeSuffix(".class").replace('/', '.') } }
        }
        return names.filter { it.startsWith("com.namanbansal.forge") }
            .mapNotNull { runCatching { Class.forName(it, false, ForgeApp::class.java.classLoader) }.getOrNull() }
    }

    @Test fun everyConcreteViewModelHasAReflectableConstructor() {
        val vms = appClasses().filter { ViewModel::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) && !it.isInterface }
        assertTrue("scan found suspiciously few ViewModels: ${vms.size}", vms.size > 40)
        val app = android.app.Application::class.java
        val bad = vms.filter { c ->
            c.name !in factoryCreated && run {
                val ok = c.constructors.any { k ->
                    val p = k.parameterTypes
                    Modifier.isPublic(k.modifiers) && (
                        p.isEmpty() ||
                        (p.size == 1 && p[0] == app && AndroidViewModel::class.java.isAssignableFrom(c)) ||
                        (p.size == 1 && p[0] == SavedStateHandle::class.java) ||
                        (p.size == 2 && p[0] == app && p[1] == SavedStateHandle::class.java && AndroidViewModel::class.java.isAssignableFrom(c))
                    )
                }
                !ok
            }
        }
        assertTrue("ViewModels the default factory cannot create: ${bad.map { it.name }}", bad.isEmpty())
    }

    companion object {
        /** ViewModels that are only ever created through an explicit ViewModelProvider.Factory. */
        val FACTORY_CREATED: Set<String> = emptySet()
    }
}

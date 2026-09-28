package com.android.purebilibili.core.network

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Some Android 17 ART builds let the JIT treat the value of a `static final` field as a constant
 * typed with its exact runtime class. For a Retrofit service that class is a
 * `java.lang.reflect.Proxy`, and devirtualizing a call to a proxy method crashes libart
 * (SIGSEGV in `ArtMethod::FindDexMethodIndexInOtherDexFile`). Kotlin compiles `object`, companion
 * and top-level properties to static final fields, so none of them may store a service.
 */
class RetrofitServiceFieldStructureTest {

    @Test
    fun `no static final field stores a Retrofit service`() {
        val offenders = appClasses()
            .flatMap { it.declaredFieldsOrEmpty() }
            .filter { it.isStaticFinal() && it.type.isRetrofitService() }
            .map { "${it.declaringClass.name}.${it.name}: ${it.type.simpleName}" }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "Read Retrofit services through NetworkModule with a getter " +
                "(`private val api get() = NetworkModule.api`) instead of storing them:\n" +
                offenders.joinToString(separator = "\n")
        )
    }

    private fun appClasses(): Sequence<Class<*>> {
        val loader = NetworkModule::class.java.classLoader
        return appClassFilePaths()
            .map { it.removeSuffix(".class").replace('/', '.') }
            .filter { it.startsWith("com.android.purebilibili.") }
            .mapNotNull { name ->
                try {
                    Class.forName(name, false, loader)
                } catch (_: LinkageError) {
                    null
                }
            }
    }

    /** Class file paths (`com/example/Foo.class`) from the jar or directory holding the app classes. */
    private fun appClassFilePaths(): Sequence<String> {
        val location = File(NetworkModule::class.java.protectionDomain.codeSource.location.toURI())
        val paths = if (location.isDirectory) {
            location.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(location).invariantSeparatorsPath }
                .toList()
        } else {
            JarFile(location).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        }
        return paths.asSequence().filter { it.endsWith(".class") }
    }

    private fun Class<*>.declaredFieldsOrEmpty(): Sequence<Field> =
        try {
            declaredFields.asSequence()
        } catch (_: LinkageError) {
            emptySequence()
        }

    private fun Field.isStaticFinal(): Boolean =
        Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)

    private fun Class<*>.isRetrofitService(): Boolean =
        isInterface && declaredMethods.any { method ->
            method.annotations.any { it.annotationClass.java.name.startsWith("retrofit2.http.") }
        }
}

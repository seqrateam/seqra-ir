package org.seqra.ir.testing

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.condition.JRE
import java.io.File

val allClasspath: List<File>
    get() {
        return classpath.map { File(it) }
    }

val guavaLib: File
    get() {
        val guavaUrl = classpath.first { it.contains("guava-") }
        return File(guavaUrl).also {
            Assertions.assertTrue(it.isFile && it.exists())
        }
    }

val jgitLib: File
    get() {
        val jgitUrl = classpath.first { it.contains("jgit-") }
        return File(jgitUrl).also {
            Assertions.assertTrue(it.isFile && it.exists())
        }
    }

val commonsCompressLib: File
    get() {
        val commonsCompressUrl = classpath.first { it.contains("commons-compress-") }
        return File(commonsCompressUrl).also {
            Assertions.assertTrue(it.isFile && it.exists())
        }
    }

val asmLib: File
    get() {
        val asmUrl = classpath.first { it.contains("${File.separator}asm${File.separator}") }
        return File(asmUrl).also {
            Assertions.assertTrue(it.isFile && it.exists())
        }
    }

val kotlinxCoroutines: File
    get() {
        val coroutines = classpath.first { it.contains("kotlinx-coroutines-") }
        return File(coroutines).also {
            Assertions.assertTrue(it.isFile && it.exists())
        }
    }

val kotlinStdLib: File
    get() {
        val kotlinStdLib = classpath.first { it.contains("${File.separator}kotlin-stdlib${File.separator}") }
        return File(kotlinStdLib).also {
            Assertions.assertTrue(it.isFile && it.exists())
        }
    }

val allJars: List<File>
    get() {
        return classpath.filter { it.endsWith(".jar") }.map { File(it) }
    }


private val classpath: List<String>
    get() {
        val classpath = System.getProperty("java.class.path")
        return classpath.split(File.pathSeparatorChar)
            .filter { !it.contains("sootup") }
    }


inline fun skipAssertionsOn(jre: JRE, assertions: () -> Unit) {
    val currentVersion = JRE.currentVersion()
    if (currentVersion != jre) {
        assertions()
    }
}
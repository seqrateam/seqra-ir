package org.seqra.ir.impl.fs

import mu.KLogging
import org.seqra.ir.api.jvm.JavaVersion
import org.seqra.ir.api.jvm.JIRByteCodeLocation
import java.io.File
import java.nio.file.Paths
import java.util.jar.JarFile

val logger = object : KLogging() {}.logger

/**
 * Returns collection of `JIRByteCodeLocation` of a file or directory.
 * Any jar file can have its own classpath defined in the manifest, that's why the method returns collection.
 * The method called of different files can have same locations in the result, so use `distinct()` to
 * filter duplicates out.
 */
fun File.asByteCodeLocation(runtimeVersion: JavaVersion, isRuntime: Boolean = false): Collection<JIRByteCodeLocation> {
    if (!exists()) {
        throw IllegalArgumentException("file $absolutePath doesn't exist")
    }
    return if (isJar()) {
        mutableSetOf<File>().also { classPath(it) }.map { JarLocation(it, isRuntime, runtimeVersion) }
    } else if (isDirectory) {
        listOf(BuildFolderLocation(this))
    } else {
        error("$absolutePath is nether a jar file nor a build directory")
    }
}

fun Collection<File>.filterExisting(): List<File> = filter { file ->
    file.exists().also {
        if (!it) {
            logger.warn("${file.absolutePath} doesn't exists. make sure there is no mistake")
        }
    }
}

private fun File.classPath(classpath: MutableCollection<File>) {
    if (exists() && classpath.add(this) && isJar()) {
        JarFile(this).use { jarFile ->
            jarFile.manifest?.mainAttributes?.getValue("Class-Path")?.classpathFiles()?.forEach { ref ->
                Paths.get(ref).toFile().classPath(classpath)
            }
        }
    }
}

fun File.isJar() = isFile && name.endsWith(".jar") || name.endsWith(".jmod")

private const val file = "file:"

private fun String.classpathFiles(): List<String> {
    val fileOffsets = mutableListOf<Int>()
    var prevOffset = -1
    while (true) {
        val offset = indexOf(file, prevOffset + 1)
        if (offset == -1) break
        fileOffsets.add(offset)
        prevOffset = offset
    }
    if (fileOffsets.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    for (i in 1 until fileOffsets.size) {
        result += substring(fileOffsets[i - 1] + file.length, fileOffsets[i]).remove0d0aFix20()
    }
    return result.also {
        it += substring(fileOffsets.last() + file.length).remove0d0aFix20()
    }
}

private fun String.remove0d0aFix20() = replace("\r", "")
    .replace("\n", "")
    .replace(" ", "")
    .replace("%20", " ")
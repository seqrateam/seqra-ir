package org.seqra.ir.util.io

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path

fun File.inputStream(): InputStream = FileInputStream(this).buffered()

fun Path.inputStream(): InputStream = toFile().inputStream()

fun File.mapReadonly(): MappedByteBuffer = FileInputStream(this).use {
    it.channel.map(FileChannel.MapMode.READ_ONLY, 0L, length())
}
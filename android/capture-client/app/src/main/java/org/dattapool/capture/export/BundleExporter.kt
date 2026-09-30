package org.dattapool.capture.export

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BundleExporter {

    fun createZipBundle(sessionDir: File): File {
        val zipFile = File(sessionDir.parentFile, "${sessionDir.name}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sessionDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.endsWith(".tmp")) {
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
        }
        return zipFile
    }

    fun getCompensationReceiptFile(sessionDir: File): File? {
        val f = File(sessionDir, "compensation.json")
        return if (f.exists() && f.length() > 0) f else null
    }
}

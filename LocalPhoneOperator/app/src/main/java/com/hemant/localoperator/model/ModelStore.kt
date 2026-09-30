package com.hemant.localoperator.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ModelStore {
    suspend fun importModel(context: Context, uri: Uri, slot: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(dir, "$slot.litertlm")
        val temp = File(dir, "$slot.importing")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected model" }
            temp.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
        }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Could not finalize model file" }
        target
    }
}

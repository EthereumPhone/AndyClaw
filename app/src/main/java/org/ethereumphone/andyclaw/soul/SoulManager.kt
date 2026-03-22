package org.ethereumphone.andyclaw.soul

import android.content.Context
import java.io.File

class SoulManager(context: Context) {

    private val file = File(context.filesDir, "soul.md")

    fun exists(): Boolean = file.exists() && file.length() > 0

    fun read(): String? = if (exists()) file.readText() else null

    fun write(content: String) {
        file.writeText(content)
    }

    fun delete() {
        if (file.exists()) file.delete()
    }
}

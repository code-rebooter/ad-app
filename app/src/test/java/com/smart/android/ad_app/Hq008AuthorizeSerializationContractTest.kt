package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008AuthorizeSerializationContractTest {

    @Test
    fun `hq008 authorize response fields should be pinned with SerializedName for release parsing`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt")

        assertTrue(source.contains("@field:SerializedName(\"authorized\")"))
        assertTrue(source.contains("@field:SerializedName(\"client_ip\")"))
        assertTrue(source.contains("@field:SerializedName(value = \"floating_width\", alternate = [\"floatingWidth\"])"))
        assertTrue(source.contains("@field:SerializedName(value = \"floating_height\", alternate = [\"floatingHeight\"])"))
        assertTrue(source.contains("@field:SerializedName(value = \"floating_x\", alternate = [\"floatingX\"])"))
        assertTrue(source.contains("@field:SerializedName(value = \"floating_y\", alternate = [\"floatingY\"])"))
        assertTrue(source.contains("@field:SerializedName(\"hidden_mode\")"))
        assertTrue(source.contains("@field:SerializedName(\"next_request_seconds\")"))
        assertTrue(source.contains("@field:SerializedName(\"request_id\")"))
    }

    private fun readProjectFile(relativePath: String): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, relativePath).exists() }
        if (projectRoot == null) {
            fail("无法定位项目根目录: ${workingDir.absolutePath}")
        }
        return File(projectRoot, relativePath).readText()
    }
}

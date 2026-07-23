package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpNormalPopupHostContractTest {

    @Test
    fun `hq008 customer flow must always use the normal forced cmp popup entry`() {
        val managerSource = readProjectFile(
            "app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt"
        )
        val activitySource = readProjectFile(
            "app/src/hq008/java/com/smart/android/ad_app/Hq008CmpConsentActivity.kt"
        )

        assertTrue(activitySource.contains("CmpDisplayType.CMP_POP"))
        assertTrue(activitySource.contains("buildCmpConfig(this, forcePopup = true)"))
        assertFalse(managerSource.contains("CmpPopStateManager"))
        assertFalse(managerSource.contains("loadPopState"))
        assertFalse(activitySource.contains("CMP_NOT_POP"))
    }

    @Test
    fun `cmp host must stay transparent until the sdk popup is visible`() {
        val manifestSource = readProjectFile("app/src/hq008/AndroidManifest.xml")
        val themeSource = readProjectFile("app/src/hq008/res/values/styles.xml")

        assertTrue(
            manifestSource.contains(
                "android:theme=\"@style/Theme.Hq008.CmpHost\""
            )
        )
        assertTrue(themeSource.contains("<item name=\"android:windowIsTranslucent\">true</item>"))
        assertTrue(themeSource.contains("<item name=\"android:windowBackground\">@android:color/transparent</item>"))
        assertTrue(themeSource.contains("<item name=\"android:backgroundDimEnabled\">false</item>"))
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

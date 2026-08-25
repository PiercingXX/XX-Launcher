package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.data.DefaultLayoutSeeder
import com.piercingxx.xxlauncher.data.DefaultLayoutSeeder.ResolvedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLayoutSeederTest {

    /** Resolves only the packages in [installed]; labels map to packages via [labels]. */
    private class FakeResolver(
        private val installed: Set<String>,
        private val labels: Map<String, String> = emptyMap(),
        private val dialer: String? = null,
        private val sms: String? = null,
        private val camera: String? = null,
        private val calculator: String? = null,
    ) : DefaultLayoutSeeder.AppResolver {
        override fun resolvePackage(packageName: String): ResolvedApp? =
            if (packageName in installed) {
                ResolvedApp(packageName, "$packageName.Main", packageName)
            } else null

        override fun resolveByLabel(label: String): ResolvedApp? =
            labels[label]?.let { ResolvedApp(it, "$it.Main", label) }

        override fun resolveDialer(): ResolvedApp? = dialer?.let(::resolvePackage)
        override fun resolveSmsApp(): ResolvedApp? = sms?.let(::resolvePackage)
        override fun resolveCameraApp(): ResolvedApp? = camera?.let(::resolvePackage)
        override fun resolveCalculator(): ResolvedApp? = calculator?.let(::resolvePackage)
    }

    @Test
    fun fullInstallSeedsAllFiveSlotsInOrder() {
        val installed = setOf(
            "com.google.android.keep",
            "com.audiobookshelf.app",
            "com.google.android.apps.youtube.music",
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.google.android.gm",
            "com.synology.dschat",
            "cz.acrobits.softphone.cloudphone",
            "com.google.android.calendar",
            "net.waterfox.android.release",
            "com.google.android.calculator",
            "com.google.android.GoogleCamera",
            "com.synology.projectkailash",
        )
        val plan = DefaultLayoutSeeder.plan(
            FakeResolver(
                installed = installed,
                labels = mapOf("Skippy" to "app.skippy.pwa"),
                dialer = "com.google.android.dialer",
                sms = "com.google.android.apps.messaging",
                camera = "com.google.android.GoogleCamera",
                calculator = "com.google.android.calculator",
            )
        )

        assertEquals(
            listOf("Notes", "Audio", "Comms", "Calendar", "Tools"),
            plan.slots.map { it.label },
        )
        assertEquals(
            listOf("Audiobook", "Music"),
            plan.slots[1].folderMembers.map { it.label },
        )
        assertEquals(
            listOf("Phone", "Text", "Email", "Chat", "SoftPhone"),
            plan.slots[2].folderMembers.map { it.label },
        )
        assertEquals(
            listOf("Waterfox", "Calculator", "Camera", "Photos"),
            plan.slots[4].folderMembers.map { it.label },
        )
        assertEquals("app.skippy.pwa", plan.swipeLeft?.packageName)
        assertEquals("com.google.android.GoogleCamera", plan.swipeRight?.packageName)
        assertEquals("Notes", plan.renameLabels["com.google.android.keep"])
        assertEquals("Music", plan.renameLabels["com.google.android.apps.youtube.music"])
        assertTrue(plan.hiddenPackages.contains("com.google.android.youtube"))
    }

    @Test
    fun unresolvedAppsAndEmptyFoldersAreSkipped() {
        val plan = DefaultLayoutSeeder.plan(
            FakeResolver(installed = setOf("com.android.dialer"))
        )

        // Only Comms survives: just the dialer resolved via package fallback.
        assertEquals(listOf("Comms"), plan.slots.map { it.label })
        assertEquals(listOf("Phone"), plan.slots[0].folderMembers.map { it.label })
        assertEquals("com.android.dialer", plan.slots[0].folderMembers[0].packageName)
        assertNull(plan.swipeLeft)
        assertNull(plan.swipeRight)
    }

    @Test
    fun fallbackPackagesResolveWhenIntentResolutionFails() {
        val plan = DefaultLayoutSeeder.plan(
            FakeResolver(
                installed = setOf(
                    "com.android.dialer",
                    "com.android.messaging",
                    "com.android.camera2",
                    "com.android.calculator2",
                ),
            )
        )

        val comms = plan.slots.first { it.label == "Comms" }
        assertEquals(listOf("Phone", "Text"), comms.folderMembers.map { it.label })
        val tools = plan.slots.first { it.label == "Tools" }
        assertEquals(listOf("Calculator", "Camera"), tools.folderMembers.map { it.label })
        assertEquals("com.android.camera2", plan.swipeRight?.packageName)
    }

    @Test
    fun nothingInstalledPlansNothing() {
        val plan = DefaultLayoutSeeder.plan(FakeResolver(installed = emptySet()))
        assertTrue(plan.slots.isEmpty())
        assertTrue(plan.renameLabels.isEmpty())
        assertNull(plan.swipeLeft)
        assertNull(plan.swipeRight)
        // The hidden-list still applies so preinstalled noise stays out of the drawer.
        assertTrue(plan.hiddenPackages.isNotEmpty())
    }
}

package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.data.DefaultLayoutSeeder
import com.piercingxx.xxlauncher.data.DefaultLayoutSeeder.ResolvedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLayoutSeederTest {

    /**
     * Resolves only the packages in [installed], reporting each app's own
     * name from [names]; labels map to packages via [labels].
     */
    private class FakeResolver(
        private val installed: Set<String>,
        private val names: Map<String, String> = emptyMap(),
        private val labels: Map<String, String> = emptyMap(),
    ) : DefaultLayoutSeeder.AppResolver {
        override fun resolvePackage(packageName: String): ResolvedApp? =
            if (packageName in installed) {
                ResolvedApp(packageName, "$packageName.Main", names[packageName] ?: packageName)
            } else null

        override fun resolveByLabel(label: String): ResolvedApp? =
            labels[label]?.let { ResolvedApp(it, "$it.Main", label) }
    }

    private val suite = mapOf(
        "com.piercingxx.xxnote" to "XX-Note",
        "com.piercingxx.audiobook" to "Audiobook",
        "com.skpp.radio" to "SKPP Radio",
        "com.piercingxx.xxdialer" to "XX-Dialer",
        "com.piercingxx.txxt" to "TxxT",
        "dev.xxemail" to "XX Email",
        "com.piercingxx.calendar" to "XX-Calendar",
        "com.piercingxx.xxcalculator" to "XX-Calculator",
        "com.piercingxx.camera" to "XX Camera",
        "com.piercingxx.photos" to "xx-photos",
    )

    @Test
    fun fullSuiteSeedsAllFiveSlotsInOrder() {
        val plan = DefaultLayoutSeeder.plan(
            FakeResolver(
                installed = suite.keys,
                names = suite,
                labels = mapOf("Skippy" to "app.skippy.pwa"),
            )
        )

        // App slots show the app's own name; only folders carry a name of their own.
        assertEquals(
            listOf("XX-Note", "Audio", "Comms", "XX-Calendar", "Tools"),
            plan.slots.map { it.label },
        )
        assertEquals(
            listOf("com.piercingxx.audiobook", "com.skpp.radio"),
            plan.slots[1].folderMembers.map { it.packageName },
        )
        assertEquals(
            listOf("com.piercingxx.xxdialer", "com.piercingxx.txxt", "dev.xxemail"),
            plan.slots[2].folderMembers.map { it.packageName },
        )
        assertEquals(
            listOf("com.piercingxx.xxcalculator", "com.piercingxx.camera", "com.piercingxx.photos"),
            plan.slots[4].folderMembers.map { it.packageName },
        )
        assertEquals(
            listOf("XX-Calculator", "XX Camera", "xx-photos"),
            plan.slots[4].folderMembers.map { it.label },
        )
        assertEquals("app.skippy.pwa", plan.swipeLeft?.packageName)
        assertEquals("com.piercingxx.camera", plan.swipeRight?.packageName)
        // Apps keep their own names: the seeder never renames.
        assertTrue(plan.renameLabels.isEmpty())
        assertTrue(plan.hiddenPackages.contains("com.google.android.youtube"))
    }

    @Test
    fun missingSuiteAppsAndEmptyFoldersAreSkipped() {
        val plan = DefaultLayoutSeeder.plan(
            FakeResolver(installed = setOf("com.piercingxx.xxdialer", "com.piercingxx.calendar"))
        )

        assertEquals(listOf("Comms", "com.piercingxx.calendar"), plan.slots.map { it.label })
        assertEquals("com.piercingxx.xxdialer", plan.slots[0].folderMembers.single().packageName)
        assertNull(plan.swipeLeft)
        assertNull(plan.swipeRight)
    }

    @Test
    fun thirdPartyAppsNeverStandIn() {
        val plan = DefaultLayoutSeeder.plan(
            FakeResolver(
                installed = setOf(
                    "com.google.android.keep",
                    "com.google.android.dialer",
                    "com.google.android.apps.messaging",
                    "com.google.android.gm",
                    "com.google.android.calendar",
                    "com.google.android.calculator",
                    "com.google.android.GoogleCamera",
                ),
            )
        )
        assertTrue(plan.slots.isEmpty())
        assertNull(plan.swipeRight)
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

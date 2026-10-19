package dk.frem.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dk.frem.app.data.FremDatabase
import dk.frem.app.recording.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Exercises MainActivity, its real navigation, Room, and the location foreground service. */
class RecordingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation
        get() = instrumentation.uiAutomation

    private val app: FremApplication
        get() = ApplicationProvider.getApplicationContext()

    private val repository
        get() = app.recording

    private val dao
        get() = app.database.recordingDao()

    private val ownedSessions = mutableSetOf<String>()
    private var previousFixNanos = 0L

    @Test
    fun hikingSurvivesNavigationAndRecreationThenPausesResumesEditsAndDeletes() =
        withCapture { factory ->
            val id = startThroughUi(ActivityType.HIKING)
            val first = awaitSource(factory, 1)
            sendPoint(first, 0.0)
            sendPoint(first, 2.5)
            awaitPoints(id, 2)
            assertTrue(session(id).distanceMeters in 2.0..3.0)
            awaitMap(2, 1)
            captureSyntheticScreen("recording-device-live")

            // System back must leave the immersive screen without stopping capture.
            instrumentation.runOnMainSync {
                compose.activity.onBackPressedDispatcher.onBackPressed()
            }
            awaitTag("navigation_ACTIVITY")
            click("navigation_SETTINGS")
            awaitTag("settings_title")
            val beforeNavigation = session(id).durationMillis
            sendPoint(first, 5.0)
            awaitPoints(id, 3)
            assertEquals(RecordingState.RECORDING, session(id).state)
            assertTrue(session(id).durationMillis > beforeNavigation)
            assertFalse(first.closed.get())
            click("navigation_ACTIVITY")
            click("record_activity", scroll = true)
            awaitTag("recording_pause")

            compose.activityRule.scenario.recreate()
            awaitTag("recording_pause")
            sendPoint(first, 7.5)
            awaitPoints(id, 4)
            awaitMap(4, 1)
            assertEquals(
                "Recreation must retain the existing service subscription",
                1,
                factory.sources.size,
            )

            click("recording_pause")
            waitFor { stored { dao.session(id) }?.state == RecordingState.PAUSED }
            waitFor { first.closed.get() }
            awaitTag("recording_resume")
            val paused = session(id)
            val pausedPoints = stored { dao.getPoints(id) }
            val pausedDuration = repository.live.value.durationMillis
            SystemClock.sleep(1_200)
            val whilePaused = freshFix(10.0)
            assertFalse("The paused provider must be closed", first.offer(whilePaused))
            assertEquals(paused, session(id))
            assertEquals(pausedPoints, stored { dao.getPoints(id) })
            assertEquals(pausedDuration, repository.live.value.durationMillis)

            click("recording_resume")
            val resumed = awaitSource(factory, 2)
            assertNotSame(first, resumed)
            // A delayed fix from the paused interval must also be rejected by the new generation.
            assertTrue(resumed.offer(whilePaused))
            sendPoint(resumed, 12.5)
            awaitPoints(id, 5)
            assertEquals(paused.distanceMeters, session(id).distanceMeters, .001)
            sendPoint(resumed, 15.0)
            val points = awaitPoints(id, 6)
            assertEquals((0L..5L).toList(), points.map { it.sequence })
            assertEquals(1, points.take(4).map { it.segment }.distinct().size)
            assertTrue(points[4].segment > points[3].segment)
            assertEquals(points[4].segment, points[5].segment)
            assertFalse(points.any { it.elapsedRealtimeNanos == whilePaused.elapsedRealtimeNanos })
            assertTrue(
                points.zipWithNext().all { (a, b) ->
                    a.elapsedRealtimeNanos < b.elapsedRealtimeNanos
                },
            )
            assertTrue(session(id).distanceMeters in 9.0..11.0)
            assertTrue(session(id).durationMillis > paused.durationMillis)

            click("recording_finish")
            waitFor { stored { dao.session(id) }?.state == RecordingState.FINISHED }
            awaitTag("activity_detail")
            awaitMap(6, 2)
            waitFor { resumed.closed.get() }
            val finished = session(id)
            assertNull(stored { dao.activeSession() })
            assertNull(finished.activeSlot)
            assertTrue(finished.end!! >= finished.start)
            assertTrue(finished.durationMillis > 0)
            assertEquals(points, stored { dao.getPoints(id) })
            captureSyntheticScreen("recording-device-detail")

            // Recreate the saved detail, rather than only checking a ViewModel's optimistic state.
            compose.activityRule.scenario.recreate()
            awaitTag("activity_detail")
            awaitMap(6, 2)
            assertEquals(finished, session(id))
            click("activity_edit")
            val title = "Device hike ${UUID.randomUUID().toString().take(8)}"
            setActivityTitle(title)
            click("activity_edit_type_CYCLING", text = "Cycling", scroll = true)
            click("activity_edit_confirm", text = "Save changes")
            waitFor {
                stored { dao.session(id) }
                    ?.let { it.title == title && it.type == ActivityType.CYCLING } == true
            }
            assertEquals(finished.copy(title = title, type = ActivityType.CYCLING), session(id))
            assertEquals(points, stored { dao.getPoints(id) })

            click("activity_detail_back")
            awaitTag("activity_list")
            assertEditedActivityInHistory(id, title)
            click("recorded_activity_$id")
            awaitTag("activity_detail")
            click("activity_delete", scroll = true)
            click("activity_delete_confirm", text = "Delete")
            waitFor { stored { dao.session(id) } == null }
            awaitTag("activity_list")
            assertTrue(
                "Deleting a session must cascade to its saved route",
                stored { dao.getPoints(id) }.isEmpty(),
            )
            assertNull(findTag("recorded_activity_$id"))
        }

    @Test
    fun cyclingServicePersistsFreshFixesWhileTheScreenIsOff() = withCapture { factory ->
        val id = startThroughUi(ActivityType.CYCLING)
        val source = awaitSource(factory, 1)
        sendPoint(source, 0.0, speed = 4.5f)
        sendPoint(source, 5.0, speed = 4.5f)
        awaitPoints(id, 2)
        awaitMap(2, 1)
        val before = session(id)
        val power = app.getSystemService(PowerManager::class.java)
        try {
            shell("input keyevent 223") // KEYCODE_SLEEP, not the toggle key.
            waitFor { !power.isInteractive }
            sendPoint(source, 10.0, speed = 4.5f)
            sendPoint(source, 15.0, speed = 4.5f)
            val points = awaitPoints(id, 4)
            assertFalse("Fixes must reach Room before waking the phone", power.isInteractive)
            val offscreen = session(id)
            assertEquals(RecordingState.RECORDING, offscreen.state)
            assertTrue(offscreen.durationMillis > before.durationMillis)
            assertTrue(offscreen.distanceMeters > before.distanceMeters + 8.0)
            assertEquals(1, points.map { it.segment }.distinct().size)
            assertEquals(1, factory.sources.size)
            assertFalse(source.closed.get())
            assertForegroundService()
        } finally {
            wakeScreen()
        }
        awaitTag("recording_finish")
        click("recording_finish")
        waitFor { stored { dao.session(id) }?.state == RecordingState.FINISHED }
        awaitTag("activity_detail")
        awaitMap(4, 1)
        assertEquals(4, stored { dao.getPoints(id) }.size)
        waitFor { source.closed.get() }
    }

    @Test
    fun pauseAndFinishPersistTheBufferedTailBeforeClosingTheLocationSource() =
        withCapture { factory ->
            val id = startThroughUi(ActivityType.CYCLING)
            val first = awaitSource(factory, 1)
            sendPoint(first, 0.0, speed = 4.5f)
            awaitPoints(id, 1)
            SystemClock.sleep(1_100)
            first.hold(freshFix(5.0, speed = 4.5f))
            assertEquals(1, stored { dao.getPoints(id) }.size)
            click("recording_pause")
            waitFor { stored { dao.session(id) }?.state == RecordingState.PAUSED }
            awaitPoints(id, 2)
            assertTrue(first.closed.get())
            assertTrue(session(id).distanceMeters in 4.0..6.0)

            click("recording_resume")
            val resumed = awaitSource(factory, 2)
            sendPoint(resumed, 10.0, speed = 4.5f)
            awaitPoints(id, 3)
            SystemClock.sleep(1_100)
            resumed.hold(freshFix(15.0, speed = 4.5f))
            click("recording_finish")
            waitFor { stored { dao.session(id) }?.state == RecordingState.FINISHED }
            awaitTag("activity_detail")
            val points = awaitPoints(id, 4)
            assertEquals(listOf(0, 0, 1, 1), points.map { it.segment })
            assertTrue(session(id).distanceMeters in 9.0..11.0)
            assertTrue(resumed.closed.get())
        }

    @Test
    fun gmsAdapterRecordsAcrossScreenOffAndWakeThenStopsOnPauseAndFinish() {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app)
        assumeTrue(
            "Google Play services unavailable (status $availability)",
            availability == ConnectionResult.SUCCESS,
        )
        withCapture { factory ->
            val client = LocationServices.getFusedLocationProviderClient(app)
            val originalMockAppOp = mockLocationUidMode()
            val injected = mutableMapOf<Long, RecordingFix>()
            fun inject(northMeters: Double) =
                sendGmsPoint(client, northMeters).also { injected[it.elapsedRealtimeNanos] = it }

            try {
                shell("cmd appops set --uid ${app.packageName} android:mock_location allow")
                assertEquals("allow", mockLocationUidMode())
                stored { client.setMockMode(true).await() }
                // Use the production factory, including its activity-specific sampling policy.
                // All observations must travel through GMS registration and LocationCallback.
                stored { repository.resetLocationSourceFactoryForTesting() }
                val id = startThroughUi(ActivityType.CYCLING)
                // Registration is asynchronous. Reissue a fresh stationary origin until the
                // service receives it; never rely on a cached fix from before the request.
                waitFor {
                    if (stored { dao.getPoints(id) }.isEmpty()) inject(0.0)
                    stored { dao.getPoints(id) }.isNotEmpty()
                }
                awaitPoints(id, 1)
                val onScreen = inject(5.0)
                waitFor {
                    stored { dao.lastPoint(id) }?.elapsedRealtimeNanos ==
                        onScreen.elapsedRealtimeNanos
                }
                awaitPoints(id, 2)
                assertTrue(session(id).distanceMeters in 4.0..6.0)
                assertForegroundService()
                assertTrue(
                    "The fake factory must not supply this recording",
                    factory.sources.isEmpty(),
                )
                awaitMap(2, 1)

                val power = app.getSystemService(PowerManager::class.java)
                val beforeScreenOff = session(id)
                val wakeFix =
                    try {
                        shell("input keyevent 223")
                        waitFor { !power.isInteractive }
                        // The real adapter replaces its request with a batch-capable screen-off
                        // request. GMS may deliver immediately or batch these current-time fixes.
                        inject(10.0)
                        inject(15.0)
                        val offScreen = inject(20.0)
                        waitFor {
                            stored { dao.lastPoint(id) }?.elapsedRealtimeNanos ==
                                offScreen.elapsedRealtimeNanos
                        }
                        awaitPoints(id, 5)
                        assertFalse(
                            "GMS fixes must persist before waking the phone",
                            power.isInteractive,
                        )
                        assertEquals(RecordingState.RECORDING, session(id).state)
                        assertTrue(
                            session(id).distanceMeters > beforeScreenOff.distanceMeters + 13.0,
                        )
                        assertTrue(session(id).durationMillis > beforeScreenOff.durationMillis)
                        assertForegroundService()
                        // Leave another fix at the off/on boundary. Waking exercises the adapter's
                        // flush-before-reconfigure path, whether or not GMS has batched this fix.
                        inject(25.0)
                    } finally {
                        wakeScreen()
                    }
                waitFor {
                    stored { dao.lastPoint(id) }?.elapsedRealtimeNanos ==
                        wakeFix.elapsedRealtimeNanos
                }
                val afterWake = inject(30.0)
                waitFor {
                    stored { dao.lastPoint(id) }?.elapsedRealtimeNanos ==
                        afterWake.elapsedRealtimeNanos
                }
                val points = awaitPoints(id, 7)
                assertEquals((0L..6L).toList(), points.map { it.sequence })
                assertEquals(1, points.map { it.segment }.distinct().size)
                assertTrue(
                    points.zipWithNext().all { (a, b) ->
                        a.elapsedRealtimeNanos < b.elapsedRealtimeNanos
                    },
                )
                points.forEach { point ->
                    val fix = injected[point.elapsedRealtimeNanos]
                    assertNotNull("Only this test's GMS-injected fixes may enter the route", fix)
                    assertEquals(fix!!.recordedAt, point.recordedAt)
                    assertEquals(fix.latitude, point.latitude, .0000001)
                    assertEquals(fix.longitude, point.longitude, .0000001)
                    assertEquals(3f, point.accuracyMeters, .01f)
                    assertEquals(12.0, point.altitudeMeters!!, .01)
                }
                assertTrue(session(id).distanceMeters in 29.0..31.0)
                awaitMap(7, 1)

                click("recording_pause")
                waitFor { stored { dao.session(id) }?.state == RecordingState.PAUSED }
                awaitTag("recording_resume")
                assertNull(repository.capture.value)
                val paused = session(id)
                val pausedDuration = repository.live.value.durationMillis
                inject(35.0)
                stored { client.flushLocations().await() }
                SystemClock.sleep(1_200)
                assertEquals(
                    "Pause must reject later GMS callbacks",
                    points,
                    stored { dao.getPoints(id) },
                )
                assertEquals(paused, session(id))
                assertEquals(pausedDuration, repository.live.value.durationMillis)

                click("recording_finish")
                waitFor { stored { dao.session(id) }?.state == RecordingState.FINISHED }
                awaitTag("activity_detail")
                awaitMap(7, 1)
                val finished = session(id)
                assertEquals(paused.durationMillis, finished.durationMillis)
                assertEquals(paused.distanceMeters, finished.distanceMeters, .001)
                assertEquals(points, stored { dao.getPoints(id) })
                assertNull(stored { dao.activeSession() })
                assertNull(repository.capture.value)
                assertTrue(factory.sources.isEmpty())
            } finally {
                // Stop our subscription before disabling mock mode, including on assertion failure,
                // so a real provider fix cannot enter a partially completed synthetic recording.
                try {
                    stored {
                        if (dao.activeSession()?.id in ownedSessions) repository.pauseAndAwait()
                    }
                } finally {
                    try {
                        stored { client.setMockMode(false).await() }
                    } finally {
                        try {
                            shell(
                                "cmd appops set --uid ${app.packageName} android:mock_location $originalMockAppOp",
                            )
                            assertEquals(originalMockAppOp, mockLocationUidMode())
                        } finally {
                            wakeScreen()
                        }
                    }
                }
                // withCapture deletes only owned sessions and resets the production factory.
            }
        }
    }

    @Test
    fun staleUnfinishedSessionRecoversAsInterruptedWithoutStartingGpsAndCanBeFinished() {
        val database = Room.inMemoryDatabaseBuilder(app, FremDatabase::class.java).build()
        try {
            val start = System.currentTimeMillis() - 120_000
            val elapsed = SystemClock.elapsedRealtimeNanos() - 60_000_000_000L
            val stale =
                ActivitySession(
                    id = "recovery-${UUID.randomUUID()}",
                    title = "Interrupted device hike",
                    type = ActivityType.HIKING,
                    start = start,
                    zoneId = "Europe/Copenhagen",
                    distanceMeters = 12.5,
                    durationMillis = 21_000,
                    lastCheckpoint = start + 21_000,
                )
            val points =
                listOf(
                    TrackPoint(stale.id, 0, 0, start, elapsed, 55.6761, 12.5683, 3f),
                    TrackPoint(
                        stale.id,
                        1,
                        0,
                        start + 10_000,
                        elapsed + 10_000_000_000L,
                        55.6762,
                        12.5683,
                        3f,
                    ),
                )
            stored {
                database.recordingDao().insertSession(stale)
                points.forEach { database.recordingDao().insertPoint(it) }
            }
            val recovered = RecordingRepository(app, database)
            val factory = FakeSourceFactory()
            stored {
                recovered.setLocationSourceFactoryForTesting(factory::create)
                recovered.awaitReady()
            }
            assertEquals(
                stale.copy(state = RecordingState.INTERRUPTED),
                stored { recovered.active.first() },
            )
            assertEquals(points, stored { recovered.getPoints(stale.id) })
            assertEquals(21_000L, recovered.live.value.durationMillis)
            assertNull(recovered.capture.value)
            assertTrue(factory.sources.isEmpty())

            val finished = stored { recovered.finishAndAwait() }!!
            assertEquals(RecordingState.FINISHED, finished.state)
            assertEquals(stale.durationMillis, finished.durationMillis)
            assertEquals(stale.distanceMeters, finished.distanceMeters, 0.0)
            assertEquals(stale.title, finished.title)
            assertEquals(stale.type, finished.type)
            assertEquals(stale.start, finished.start)
            assertEquals(stale.zoneId, finished.zoneId)
            assertNotNull(finished.end)
            assertNull(finished.activeSlot)
            assertNull(stored { recovered.active.first() })
            assertEquals(finished, stored { database.recordingDao().session(stale.id) })
            assertEquals(points, stored { database.recordingDao().getPoints(stale.id) })
            assertTrue(
                "Recovery and finish must not subscribe to a location source",
                factory.sources.isEmpty(),
            )
        } finally {
            database.close()
        }
    }

    private fun withCapture(test: (FakeSourceFactory) -> Unit) {
        stored { repository.awaitReady() }
        assumeTrue(
            "Leave an existing personal recording untouched",
            stored { dao.activeSession() } == null,
        )
        val location = app.getSystemService(LocationManager::class.java)
        val locationWasEnabled = location.isLocationEnabled
        val info = automation.serviceInfo
        val originalFlags = info.flags
        val uiPreferences = app.getSharedPreferences("frem_recording_ui", Context.MODE_PRIVATE)
        val previousChoices =
            listOf("last_type", "recent_types").associateWith { uiPreferences.getString(it, null) }
        val existing = stored { dao.sessions().first() }.associateBy { it.id }
        val factory = FakeSourceFactory()
        var installed = false
        try {
            info.flags =
                info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
            wakeScreen()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                listOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                    .forEach { grant(it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                grant(Manifest.permission.POST_NOTIFICATIONS)
            shell("cmd location set-location-enabled true")
            waitFor { location.isLocationEnabled }
            stored { repository.setLocationSourceFactoryForTesting(factory::create) }
            installed = true
            test(factory)
        } finally {
            try {
                wakeScreen()
            } finally {
                try {
                    stored {
                        ownedSessions.forEach { repository.delete(it) }
                        if (installed) repository.resetLocationSourceFactoryForTesting()
                    }
                } finally {
                    uiPreferences
                        .edit()
                        .apply {
                            previousChoices.forEach { (key, value) ->
                                if (value == null) remove(key) else putString(key, value)
                            }
                        }
                        .commit()
                    if (!locationWasEnabled) shell("cmd location set-location-enabled false")
                    info.flags = originalFlags
                    automation.serviceInfo = info
                }
            }
        }
        assertTrue(
            "All test location subscriptions must be released",
            factory.sources.all { it.closed.get() },
        )
        val remaining = stored { dao.sessions().first() }.associateBy { it.id }
        existing.forEach { (id, session) ->
            assertEquals("Unrelated recording $id changed", session, remaining[id])
        }
    }

    private fun startThroughUi(type: ActivityType): String {
        click("navigation_ACTIVITY")
        click("record_activity", scroll = true)
        click("recording_type_${type.name}", scroll = true)
        val previous = stored { dao.sessions().first() }.map { it.id }.toSet()
        try {
            click("recording_start")
            waitFor {
                stored { dao.activeSession() }
                    ?.let { session ->
                        if (session.id !in previous) ownedSessions.add(session.id)
                        session.id in ownedSessions
                    } == true
            }
        } finally {
            stored { dao.activeSession() }
                ?.takeIf { it.id !in previous }
                ?.let { ownedSessions.add(it.id) }
        }
        val session = stored { dao.activeSession() }!!
        assertEquals(type, session.type)
        assertEquals(RecordingState.RECORDING, session.state)
        awaitTag("recording_pause")
        return session.id
    }

    private fun awaitSource(factory: FakeSourceFactory, count: Int): FakeLocationSource {
        waitFor { factory.sources.size == count && factory.sources.last().collected.get() }
        assertForegroundService()
        return factory.sources.last()
    }

    @Suppress("DEPRECATION")
    private fun assertForegroundService() {
        assertTrue(
            "Capture must belong to the real location foreground service",
            app.getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
                .any {
                    it.service.className == RecordingService::class.java.name && it.foreground
                },
        )
    }

    private fun sendPoint(source: FakeLocationSource, northMeters: Double, speed: Float = 2.0f) {
        // Real spacing keeps both Doppler speed and displacement physically plausible. Never
        // pre-generate a route with future timestamps or timestamps predating start/resume.
        val remaining = 1_100 - (SystemClock.elapsedRealtimeNanos() - previousFixNanos) / 1_000_000
        if (remaining > 0) SystemClock.sleep(remaining)
        assertTrue(
            "The service's current source must accept fixes",
            source.offer(freshFix(northMeters, speed)),
        )
    }

    @SuppressLint("MissingPermission") // withCapture grants precise location before this runs.
    private fun sendGmsPoint(
        client: FusedLocationProviderClient,
        northMeters: Double,
    ): RecordingFix {
        val remaining = 1_100 - (SystemClock.elapsedRealtimeNanos() - previousFixNanos) / 1_000_000
        if (remaining > 0) SystemClock.sleep(remaining)
        val fix = freshFix(northMeters, speed = 4.5f)
        val location =
            Location("fused").apply {
                time = fix.recordedAt
                elapsedRealtimeNanos = fix.elapsedRealtimeNanos
                latitude = fix.latitude
                longitude = fix.longitude
                accuracy = fix.accuracyMeters
                altitude = fix.altitudeMeters!!
                verticalAccuracyMeters = fix.verticalAccuracyMeters!!
                speed = fix.speedMetersPerSecond!!
                speedAccuracyMetersPerSecond = fix.speedAccuracyMetersPerSecond!!
                bearing = 0f
                bearingAccuracyDegrees = 1f
            }
        stored { client.setMockLocation(location).await() }
        return fix
    }

    private fun freshFix(northMeters: Double, speed: Float = 2.0f): RecordingFix {
        val now = SystemClock.elapsedRealtimeNanos()
        previousFixNanos = now
        return RecordingFix(
            recordedAt = System.currentTimeMillis(),
            elapsedRealtimeNanos = now,
            latitude = 55.6761 + northMeters / 111_195.0,
            longitude = 12.5683,
            accuracyMeters = 3f,
            altitudeMeters = 12.0,
            verticalAccuracyMeters = 2f,
            speedMetersPerSecond = speed,
            speedAccuracyMetersPerSecond = .2f,
        )
    }

    private fun session(id: String) = stored { dao.session(id) }!!

    private fun awaitPoints(id: String, count: Int): List<TrackPoint> {
        waitFor { stored { dao.getPoints(id) }.size >= count }
        return stored { dao.getPoints(id) }.also { assertEquals(count, it.size) }
    }

    private fun assertEditedActivityInHistory(id: String, title: String) {
        val tag = "recorded_activity_$id"
        try {
            // History has no active native map. Use one synchronized Compose tree for scrolling
            // and text checks: an accessibility scroll can expose a card mid-animation, then
            // dispose that lazy item before performScrollTo() queries it in Compose.
            compose.onNodeWithTag("activity_list").performScrollToNode(hasTestTag(tag))
            val card = compose.onNodeWithTag(tag).performScrollTo()
            waitFor {
                compose
                    .onAllNodes(
                        hasTestTag(tag) and
                            hasText(title, substring = true) and
                            hasText("Cycling", substring = true),
                    )
                    .fetchSemanticsNodes()
                    .size == 1
            }
            card
                .assertIsDisplayed()
                .assertTextContains(title, substring = true)
                .assertTextContains("Cycling", substring = true)
                .assert(hasText("Hiking", substring = true).not())
        } catch (failure: Throwable) {
            // Capture before withCapture deletes the test session. Include the current Compose
            // tree and durable test row in the failure itself, so CI log-only artifacts suffice.
            runCatching { captureSyntheticScreen("recording-device-history-failure") }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            val tree = runCatching {
                compose.onRoot(useUnmergedTree = true).printToString()
            }
                .getOrElse { "Compose semantics unavailable: $it" }
            val saved = runCatching { stored { dao.session(id) } }
            throw AssertionError(
                "Expected history card $tag with title '$title' and Cycling, without Hiking.\n" +
                    "Saved test session: $saved\nCompose semantics:\n$tree",
                failure,
            )
        }
    }

    private fun awaitMap(points: Int, segments: Int) {
        awaitTag("activity_map")
        waitFor {
            findNode {
                it.contentDescription?.contains(
                    "OpenStreetMap. $points recorded ${if (points == 1) "point" else "points"} in $segments route ${if (segments == 1) "segment" else "segments"}.",
                ) == true
            } != null
        }
        var view: MapView? = null
        waitFor {
            instrumentation.runOnMainSync { view = compose.activity.window.decorView.visibleMap() }
            view != null
        }
        val mapView = view!!
        var map: MapLibreMap? = null
        instrumentation.runOnMainSync { mapView.getMapAsync { map = it } }
        // Verify the real GL renderer has a route, without depending on online basemap tiles.
        waitFor {
            var rendered = false
            instrumentation.runOnMainSync {
                map?.let { ready ->
                    if (ready.style?.getLayer("frem-route-line") != null) {
                        rendered =
                            ready
                                .queryRenderedFeatures(
                                    RectF(
                                        0f,
                                        0f,
                                        mapView.width.toFloat(),
                                        mapView.height.toFloat(),
                                    ),
                                    "frem-route-line",
                                )
                                .isNotEmpty()
                    }
                }
            }
            rendered
        }
    }

    private fun View.visibleMap(): MapView? {
        if (!isShown) return null
        if (this is MapView && width > 0 && height > 0) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).visibleMap()?.let {
                return it
            }
        }
        return null
    }

    // Native MapView can keep Espresso/Compose idleness busy. These accessibility actions and
    // waitUntil predicates do not wait for GL/network idleness and leave the real map rendering.
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000, condition)

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // Compose can replace the semantics of a button without replacing its native host view.
        // Refresh the accessibility cache so Pause/Resume queries see the latest semantics.
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return null
        // Some cold-booted Google APIs images show an unrelated Pixel Launcher ANR over the
        // foreground activity. Dismiss only that launcher's dialog; a Frem ANR must still fail.
        val launcherError = root.find {
            it.viewIdResourceName == "android:id/alertTitle" &&
                it.text?.toString()?.let { title ->
                    title.startsWith("Pixel Launcher") && title.contains("respond")
                } == true
        }
        if (launcherError != null) {
            root
                .find { it.viewIdResourceName == "android:id/aerr_close" }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return null
        }
        return root.find(predicate)
    }

    private fun AccessibilityNodeInfo.find(
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (isVisibleToUser && predicate(this)) return this
        for (index in 0 until childCount) getChild(index)?.find(predicate)?.let {
            return it
        }
        return null
    }

    private fun findTag(tag: String) = findNode {
        it.viewIdResourceName == tag || it.viewIdResourceName == "${app.packageName}:id/$tag"
    }

    private fun awaitTag(
        tag: String,
        text: String? = null,
        scroll: Boolean = false,
    ): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        var lastScroll = 0L
        try {
            waitFor {
                node =
                    findTag(tag)
                        ?: text?.let { expected -> findNode { it.text?.toString() == expected } }
                if (node == null && scroll && SystemClock.uptimeMillis() - lastScroll >= 300) {
                    findNode { it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    lastScroll = SystemClock.uptimeMillis()
                }
                node != null
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            captureSyntheticScreen("recording-device-failure")
            fun describe(node: AccessibilityNodeInfo?, depth: Int = 0): String {
                if (node == null) return ""
                return buildString {
                    append(" ".repeat(depth))
                    appendLine(
                        "${node.viewIdResourceName}: ${node.text} (${node.contentDescription}) visible=${node.isVisibleToUser}",
                    )
                    for (index in 0 until node.childCount) append(
                        describe(node.getChild(index), depth + 1),
                    )
                }
            }
            throw AssertionError(
                "Waiting for $tag\n${describe(automation.rootInActiveWindow)}",
                error,
            )
        }
        return node!!
    }

    private fun click(tag: String, text: String? = null, scroll: Boolean = false) {
        awaitTag(tag, text, scroll)
        var node: AccessibilityNodeInfo? = null
        waitFor {
            node =
                findTag(tag)
                    ?: text?.let { expected -> findNode { it.text?.toString() == expected } }
            while (node != null && !node!!.isClickable) node = node!!.parent
            node?.isEnabled == true
        }
        assertTrue(
            "Click failed for $tag",
            node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
    }

    private fun setActivityTitle(title: String) {
        var input: AccessibilityNodeInfo? = null
        waitFor {
            input = findTag("activity_title_input") ?: findNode { it.isEditable }
            input != null
        }
        assertTrue(
            input!!.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        title,
                    )
                },
            ),
        )
    }

    private fun grant(permission: String) {
        shell("pm grant ${app.packageName} $permission")
        assertEquals(
            permission,
            PackageManager.PERMISSION_GRANTED,
            app.checkSelfPermission(permission),
        )
    }

    private fun mockLocationUidMode(): String {
        val output = shell("cmd appops get --uid ${app.packageName} android:mock_location")
        val mode =
            Regex(
                    "(?m)^\\s*(?:Uid mode:\\s*)?MOCK_LOCATION:\\s*(allow|ignore|deny|default|foreground)\\b",
                )
                .find(output)
                ?.groupValues
                ?.get(1)
        // With no UID override, restore the printed platform default (normally deny). The literal
        // mode "default" is distinct and could leave an override that was not present before.
        val platformDefault =
            Regex(
                    "(?m)^Default mode:\\s*(allow|ignore|deny|default|foreground)\\b",
                )
                .find(output)
                ?.groupValues
                ?.get(1)
        return mode
            ?: platformDefault
            ?: error("Cannot read the original mock-location app-op: $output")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun wakeScreen() {
        shell("input keyevent 224") // KEYCODE_WAKEUP: always restore wake, including on failure.
        shell("wm dismiss-keyguard")
        waitFor { app.getSystemService(PowerManager::class.java).isInteractive }
    }

    private fun captureSyntheticScreen(name: String) {
        // Synthetic recording/detail and failure diagnostics stay in app-internal cache.
        val bitmap = automation.takeScreenshot()
        assertNotNull("UiAutomation screenshot unavailable", bitmap)
        try {
            File(app.cacheDir, "$name.png").outputStream().use {
                assertTrue(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap?.recycle()
        }
    }

    private fun <T> stored(block: suspend () -> T): T = runBlocking {
        withTimeout(5_000) { block() }
    }

    private class FakeSourceFactory {
        val sources = CopyOnWriteArrayList<FakeLocationSource>()

        fun create(): RecordingLocationSource = FakeLocationSource().also { sources.add(it) }
    }

    private class FakeLocationSource : RecordingLocationSource {
        private val incoming = Channel<RecordingLocationEvent>(Channel.UNLIMITED)
        private val held = mutableListOf<RecordingFix>()
        private val barriers = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        val collected = AtomicBoolean(false)
        val closed = AtomicBoolean(false)

        override fun events(): Flow<RecordingLocationEvent> = flow {
            check(collected.compareAndSet(false, true)) { "Each start/resume needs a fresh source" }
            emitAll(incoming.receiveAsFlow())
        }

        fun offer(fix: RecordingFix) = incoming.trySend(RecordingLocationEvent.Fix(fix)).isSuccess

        fun hold(fix: RecordingFix) {
            check(!closed.get())
            synchronized(held) { held.add(fix) }
        }

        override suspend fun flush() {
            if (closed.get()) return
            val completion = CompletableDeferred<Unit>()
            barriers.add(completion)
            try {
                val tail =
                    synchronized(held) {
                        held.sortedBy { it.elapsedRealtimeNanos }.also { held.clear() }
                    }
                tail.forEach { incoming.send(RecordingLocationEvent.Fix(it)) }
                incoming.send(RecordingLocationEvent.Barrier(completion))
                completion.await()
            } finally {
                barriers.remove(completion)
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                incoming.cancel()
                barriers.forEach { it.cancel() }
                synchronized(held) { held.clear() }
            }
        }
    }
}

package dev.teslanavi.navigation

import org.junit.Assert.*
import org.junit.Test

class OverSpeedPolicyTest {
    @Test fun nextCameraGetsItsOwnLimitAnnouncementDuringContinuousOverspeed() {
        val policy = OverSpeedPolicy()
        policy.commit(checkNotNull(policy.next(camera, 85.0, RoadClass.GENERAL, 0)))
        val second = checkNotNull(policy.next(camera.copy(id = "b", speedLimitKph = 80), 85.0, RoadClass.GENERAL, 1000))
        assertTrue(second.text.contains("80킬로미터"))
    }
    private val camera = CameraReading("a", CameraKind.SPEED, 60, 300)

    @Test fun warningStartsAtFiveKphAboveLimit() {
        val policy = OverSpeedPolicy()
        assertFalse(policy.isOverSpeed(camera, 64.999, RoadClass.GENERAL))
        assertTrue(policy.isOverSpeed(camera, 65.0, RoadClass.GENERAL))
        assertTrue(policy.isOverSpeed(camera, 80.0, RoadClass.GENERAL))
    }

    @Test fun unknownVariableAndInvalidLimitsDoNotProduceInventedWarnings() {
        val policy = OverSpeedPolicy()
        listOf(null, 0, -1).forEach { limit ->
            assertNull(policy.next(camera.copy(speedLimitKph = limit), 100.0, RoadClass.GENERAL, 0))
        }
        assertNull(policy.next(camera.copy(variableLimit = true), 100.0, RoadClass.GENERAL, 0))
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { speed ->
            assertNull(policy.next(camera, speed, RoadClass.GENERAL, 0))
        }
    }

    @Test fun speedWarningIsRestrictedToCameraApproachNotUnrelatedDistantLimits() {
        val policy = OverSpeedPolicy()
        assertFalse(policy.isOverSpeed(camera.copy(distanceMeters = 301), 65.0, RoadClass.GENERAL))
        assertTrue(policy.isOverSpeed(camera.copy(distanceMeters = 500), 65.0, RoadClass.HIGHWAY))
        assertFalse(policy.isOverSpeed(camera.copy(distanceMeters = 501), 65.0, RoadClass.HIGHWAY))
        assertFalse(policy.isOverSpeed(camera.copy(distanceMeters = -1), 65.0, RoadClass.HIGHWAY))
    }

    @Test fun speechOncePerOverspeedEpisodeAndRearmsAfterSlowing() {
        val policy = OverSpeedPolicy()
        val first = checkNotNull(policy.next(camera, 65.0, RoadClass.GENERAL, 0))
        assertEquals("제한속도 60킬로미터 초과입니다.", first.text)
        policy.commit(first)
        assertNull(policy.next(camera, 70.0, RoadClass.GENERAL, 60_000))
        assertNull(policy.next(camera, 64.0, RoadClass.GENERAL, 61_000))
        assertNotNull(policy.next(camera, 65.0, RoadClass.GENERAL, 62_000))
    }

    @Test fun upcomingWarningIsTheExactTextAndOnlyForUsableLimits() {
        val policy = OverSpeedPolicy()
        assertEquals(policy.next(camera, 70.0, RoadClass.GENERAL, 0)!!.text, policy.upcoming(camera.copy(distanceMeters = 1300), RoadClass.GENERAL))
        assertNull(policy.upcoming(camera.copy(distanceMeters = 1301), RoadClass.GENERAL))
        assertNull(policy.upcoming(camera.copy(variableLimit = true), RoadClass.GENERAL))
        listOf(null, 0).forEach { assertNull(policy.upcoming(camera.copy(speedLimitKph = it), RoadClass.GENERAL)) }
    }
}

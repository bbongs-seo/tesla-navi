package dev.teslanavi.navigation

import org.junit.Assert.*
import org.junit.Test

class CameraAlertPolicyTest {
    private val camera = CameraReading("a", CameraKind.SPEED, 60, 300)
    @Test fun distanceSpeechRoundsToNearestHundredMeters() {
        val policy = CameraAlertPolicy()
        listOf(423 to "400미터", 450 to "500미터", 149 to "100미터", 49 to "잠시 후").forEach { (meters, expected) ->
            assertTrue(policy.next(camera.copy(distanceMeters = meters), RoadClass.HIGHWAY, 0)!!.text.contains(expected))
        }
    }
    @Test fun oneAnnouncementPerCameraDespiteDistanceJitter() {
        val policy = CameraAlertPolicy()
        val first = checkNotNull(policy.next(camera, RoadClass.GENERAL, 0))
        policy.commit(first)
        assertNull(policy.next(camera.copy(distanceMeters = 295), RoadClass.GENERAL, 10))
        assertNull(policy.next(camera.copy(distanceMeters = 310), RoadClass.GENERAL, 20))
        assertNull(policy.next(camera.copy(distanceMeters = 190), RoadClass.GENERAL, 30))
        assertNull(policy.next(camera.copy(distanceMeters = 70), RoadClass.GENERAL, 50))
    }
    @Test fun highwayAndGeneralRoadUseExactDifferentBoundaries() {
        val policy = CameraAlertPolicy()
        assertNull(policy.next(camera.copy(distanceMeters = 501), RoadClass.HIGHWAY, 0))
        assertNotNull(policy.next(camera.copy(distanceMeters = 500), RoadClass.HIGHWAY, 0))
        assertNull(policy.next(camera.copy(distanceMeters = 301), RoadClass.GENERAL, 0))
        assertNotNull(policy.next(camera.copy(distanceMeters = 300), RoadClass.GENERAL, 0))
        assertNull(policy.next(camera.copy(distanceMeters = -1), RoadClass.HIGHWAY, 0))
    }
    @Test fun unknownRoadUsesEarlyFallbackAndLateFirstDetectionStillAlerts() {
        val policy = CameraAlertPolicy()
        assertNotNull(policy.next(camera.copy(distanceMeters = 500), RoadClass.UNKNOWN, 0))
        assertNotNull(policy.next(camera.copy(distanceMeters = 140), RoadClass.GENERAL, 0))
    }
    @Test fun variableAndUnknownLimitsAreNotInvented() {
        val policy = CameraAlertPolicy()
        val text = checkNotNull(policy.next(camera.copy(speedLimitKph = null, variableLimit = true), RoadClass.GENERAL, 0)).text
        assertFalse(text.contains("60"))
        assertTrue(text.contains("가변 제한속도 표지판"))
    }
    @Test fun cancelledRouteResetsSuppressionAndRealRevisitExpires() {
        val policy = CameraAlertPolicy()
        policy.commit(checkNotNull(policy.next(camera, RoadClass.GENERAL, 0)))
        assertNull(policy.next(camera, RoadClass.GENERAL, 1000))
        policy.clear()
        policy.commit(checkNotNull(policy.next(camera, RoadClass.GENERAL, 2000)))
        assertNotNull(policy.next(camera, RoadClass.GENERAL, 302001))
    }
}

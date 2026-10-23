package dev.teslanavi.navigation

import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafetyCode
import com.kakaomobility.knsdk.KNRoadType
import org.junit.Assert.*
import org.junit.Test

class KakaoCameraMappingTest {
    @Test fun usesSdkRoadClassificationInsteadOfVehicleSpeed() {
        assertEquals(RoadClass.HIGHWAY, KakaoNavigationEngine.roadClass(KNRoadType.KNRoadType_Highway))
        assertEquals(RoadClass.GENERAL, KakaoNavigationEngine.roadClass(KNRoadType.KNRoadType_GeneralRoad))
        assertEquals(RoadClass.UNKNOWN, KakaoNavigationEngine.roadClass(KNRoadType.KNRoadType_Unknown))
        assertEquals(RoadClass.UNKNOWN, KakaoNavigationEngine.roadClass(null))
    }
    @Test fun recognizesSpeedCamerasButIgnoresTrafficCollectionAndOtherEnforcement() {
        assertEquals(CameraKind.SPEED, KakaoNavigationEngine.kind(KNSafetyCode.KNSafetyCode_SpeedViolationCamera))
        assertEquals(CameraKind.REAR_SIGNAL_SPEED, KakaoNavigationEngine.kind(KNSafetyCode.KNSafetyCode_SignalAndSpeedViolationBackwardCamera))
        assertEquals(CameraKind.SECTION_START, KakaoNavigationEngine.kind(KNSafetyCode.KNSafetyCode_SpeedViolationSectionInOnlyCamera))
        assertNull(KakaoNavigationEngine.kind(KNSafetyCode.KNSafetyCode_TrafficCollectionCamera))
        assertNull(KakaoNavigationEngine.kind(KNSafetyCode.KNSafetyCode_ParkingViolationCamera))
    }
}

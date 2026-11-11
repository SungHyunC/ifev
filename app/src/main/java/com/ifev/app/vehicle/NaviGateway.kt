package com.ifev.app.vehicle

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class NaviPlace(val name: String, val lat: Double, val lon: Double, val poiId: String?, val poiSubId: String?)

sealed interface NaviSignal {
    data class Status(val text: String) : NaviSignal
    data class Position(val lat: Double, val lon: Double) : NaviSignal
    data class Places(val home: NaviPlace?, val office: NaviPlace?) : NaviSignal
    data class Recent(val places: List<NaviPlace>) : NaviSignal
    /** A route is set: destination name, remaining distance (m) and time (s). */
    data class Destination(val name: String, val remainMeters: Int, val remainSeconds: Int) : NaviSignal
    /**
     * The navigation's own estimate of battery (or fuel) left on arrival. The SDK comment does not give
     * a unit; the Pleos emulator's navigation sends Wh (49 216 for the 63 % it shows on a 77.4 kWh car).
     */
    data class ArrivalEstimate(val raw: Int) : NaviSignal
    /** Guidance ended, was cancelled, or the car arrived. */
    data object RouteCleared : NaviSignal
}

/** Saved places, recent destinations, position and route distances through NaviHelper. Permission: NAVI_ROUTE. */
class NaviGateway(context: Context, private val log: (String) -> Unit) {

    private val navi = NaviHelper(context.applicationContext)
    @Volatile private var connected = false
    @Volatile private var pendingTrip: CompletableDeferred<TripInfo>? = null
    private val tripLock = Mutex()

    fun signals(): Flow<NaviSignal> = callbackFlow {
        val listener = object : NaviHelperEventListener {
            override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
                fun pick(type: BookmarkType) = bookmarkInfoList.firstOrNull { it.category == type }?.let {
                    NaviPlace(it.name, it.latitude, it.longitude, it.poiId, it.poiSubId)
                }
                trySend(NaviSignal.Places(pick(BookmarkType.HOME), pick(BookmarkType.OFFICE)))
            }

            override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
                trySend(NaviSignal.Recent(recentDestinationInfoList.map { NaviPlace(it.name, it.latitude, it.longitude, it.poiId, it.poiSubId) }))
            }

            override fun onBookMarkChanged() = refreshPlaces()

            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
                trySend(NaviSignal.Position(currentLocationInfo.latitude, currentLocationInfo.longitude))
            }

            override fun onTripInfo(tripInfo: TripInfo) {
                pendingTrip?.complete(tripInfo)
            }

            // A route being set is the moment to preview it on the imagined EV.
            override fun onDestinationChanged() = askDestination()
            override fun onRoutingCompleted() = askDestination()
            override fun onReRouteEnded() = askDestination()
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) = askDestination()

            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                if (destinationInfo.remainDistance > 0) {
                    trySend(NaviSignal.Destination(destinationInfo.name, destinationInfo.remainDistance, destinationInfo.remainTime))
                }
            }

            override fun onDrivingInfo(drivingInfo: DrivingInfo) {
                val d = drivingInfo.destination
                log("내비 주행 정보: ${d.name} ${d.distance}m 도착잔량=${d.remainDestinationChargingValue}")
                if (d.distance > 0) trySend(NaviSignal.Destination(d.name, d.distance, d.duration))
                if (d.remainDestinationChargingValue > 0) trySend(NaviSignal.ArrivalEstimate(d.remainDestinationChargingValue))
            }

            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                log("내비 경로 상태: routing=${routeStateInfo.isRouting} 도착잔량=${routeStateInfo.remainDestinationChargingInt}")
                // isRouting is false during guidance on the emulator, so it does not end the route here;
                // onRouteCancelled / onDestinationArrived do. (onRouteEnded is the deprecated name of
                // onRoutingCompleted: the route search finished, not the guidance.)
                if (routeStateInfo.remainDestinationChargingInt > 0) trySend(NaviSignal.ArrivalEstimate(routeStateInfo.remainDestinationChargingInt))
            }


            override fun onRouteCancelled() { trySend(NaviSignal.RouteCleared) }
            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { trySend(NaviSignal.RouteCleared) }

            override fun onError(errorCode: NaviErrorCode) {
                log("내비 응답 오류: $errorCode")
            }
        }
        navi.addListener(listener)
        navi.initialize(
            onConnected = {
                connected = true
                trySend(NaviSignal.Status("연결됨"))
                refreshPlaces()
                askDestination()
            },
            onFailed = { e ->
                connected = false
                trySend(NaviSignal.Status("연결 실패"))
                log("내비 연결 실패: ${e.message}")
            },
        )
        awaitClose {
            connected = false
            navi.removeListener(listener)
            navi.release()
        }
    }

    /** Asks for saved and recent places; answers arrive through [signals]. */
    fun refreshPlaces() {
        if (!connected) return
        runCatching {
            navi.getBookmarkInfo()
            navi.getRecentDestinationInfo()
        }.onFailure { log("장소 조회 실패: ${it.message}") }
    }

    /** Asks for the active route; the answer arrives as [NaviSignal.Destination] (nothing when no route). */
    fun askDestination() {
        if (!connected) return
        runCatching {
            navi.getDestinationInfo()
            navi.getRouteStateInfo()
        }.onFailure { log("목적지 조회 실패: ${it.message}") }
    }

    fun requestPosition() {
        if (connected) runCatching { navi.getCurrentLocationInfo() }.onFailure { log("현재 위치 조회 실패: ${it.message}") }
    }

    /** Road distance from the current position to [place], km, or null when the navigation cannot route it. */
    suspend fun roadKmFromHere(place: NaviPlace): Double? = tripLock.withLock {
        if (!connected) return null
        val answer = CompletableDeferred<TripInfo>()
        pendingTrip = answer
        val sent = runCatching { navi.requestTrip(TripRequest(place.lat, place.lon, place.poiId ?: "", place.name, place.poiSubId ?: "")) }
        if (sent.isFailure) {
            log("경로 거리 요청 실패: ${sent.exceptionOrNull()?.message}")
            return null
        }
        val info = withTimeoutOrNull(TRIP_TIMEOUT_MS) { answer.await() }
        pendingTrip = null
        info?.distance?.takeIf { it > 0 }?.div(1000.0)
    }

    private companion object {
        const val TRIP_TIMEOUT_MS = 6_000L
    }
}

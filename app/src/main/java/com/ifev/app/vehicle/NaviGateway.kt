package com.ifev.app.vehicle

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RoutePointType
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

/** Unit of a navigation arrival estimate: known from the SDK KDoc, or not stated there. */
enum class ArrivalUnit { PERCENT, WH, UNSPECIFIED }

data class NaviPlace(val name: String, val lat: Double, val lon: Double, val poiId: String?, val poiSubId: String?)

sealed interface NaviSignal {
    data class Status(val text: String) : NaviSignal
    data class Position(val lat: Double, val lon: Double) : NaviSignal
    data class Places(val home: NaviPlace?, val office: NaviPlace?) : NaviSignal
    data class Recent(val places: List<NaviPlace>) : NaviSignal
    /** A route is set: destination name, remaining distance (m) and time (s). */
    data class Destination(val name: String, val remainMeters: Int, val remainSeconds: Int) : NaviSignal
    /**
     * The navigation's own estimate of battery (or fuel) left on arrival, in [unit]. 0 is a valid value
     * (arriving empty). DrivingState.remainDestinationChargingValue and
     * RouteStateInfo.remainDestinationChargingInt have no unit in the navi-data 3.0.4 KDoc ("battery or
     * fuel level"); the Pleos emulator's navigation sends Wh there (49 216 for the 63 % it shows on a
     * 77.4 kWh car). TripInfo.estimatedSocAtDest is documented as %.
     */
    data class ArrivalEstimate(val raw: Int, val unit: ArrivalUnit) : NaviSignal
    /** Guidance ended, was cancelled, or the car arrived. */
    data object RouteCleared : NaviSignal
}

/** Saved places, recent destinations, position and route distances through NaviHelper. Permission: NAVI_ROUTE. */
class NaviGateway(context: Context, private val log: (String) -> Unit) {

    private val navi = NaviHelper(context.applicationContext)
    @Volatile private var connected = false
    /** The trip request waiting for its reply, with the id [tripReplies] gave it. */
    @Volatile private var pendingTrip: Pair<Long, CompletableDeferred<TripInfo>>? = null
    private val tripLock = Mutex()
    private val tripReplies = TripReplyMatcher()
    /** Whether onDestinationInfo has ever answered with a remaining distance this session. */
    @Volatile private var destinationInfoSeen = false
    @Volatile private var lastDestinationAskAt = 0L

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
                // The reply has no request id: identify it by the destination on its route, so a late
                // reply to a request that already timed out is not taken as the answer to the next one.
                val dest = runCatching {
                    tripInfo.summary.routePoints.lastOrNull { it.pointType == RoutePointType.DESTINATION }
                }.getOrNull()?.let { TripReplyMatcher.Point(it.latitude, it.longitude, it.poiId) }
                val id = tripReplies.match(dest, System.currentTimeMillis())
                val pending = pendingTrip
                if (id == null || pending == null || pending.first != id) {
                    log("경로 거리 응답 버림(늦게 온 이전 요청의 응답일 수 있음)")
                    return
                }
                pending.second.complete(tripInfo)
            }

            // A route being set is the moment to preview it on the imagined EV.
            override fun onDestinationChanged() = askDestination()
            override fun onRoutingCompleted() = askDestination()
            override fun onReRouteEnded() = askDestination()
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) = askDestination()

            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                if (destinationInfo.remainDistance > 0) {
                    destinationInfoSeen = true
                    trySend(NaviSignal.Destination(destinationInfo.name, destinationInfo.remainDistance, destinationInfo.remainTime))
                }
            }

            override fun onDrivingInfo(drivingInfo: DrivingInfo) {
                val d = drivingInfo.destination
                log("내비 주행 정보: ${d.name} ${d.distance}m 도착잔량=${d.remainDestinationChargingValue}")
                // DrivingState.distance is "the distance to this point from the start" (navi-data KDoc),
                // not what is left, so the remaining distance comes from DestinationInfo.remainDistance:
                // ask for it again, at most every few seconds, while guidance runs.
                val now = System.currentTimeMillis()
                if (now - lastDestinationAskAt >= DESTINATION_ASK_EVERY_MS) {
                    lastDestinationAskAt = now
                    askDestination()
                }
                // Fallback only if this navigation never answers DestinationInfo: at route start the
                // distance from the start is the remaining distance, so it is close enough for a preview.
                if (!destinationInfoSeen && d.distance > 0) trySend(NaviSignal.Destination(d.name, d.distance, d.duration))
                // DrivingInfo only comes during guidance, so 0 here is a real "arrive empty", not a default.
                if (d.remainDestinationChargingValue >= 0) {
                    trySend(NaviSignal.ArrivalEstimate(d.remainDestinationChargingValue, ArrivalUnit.UNSPECIFIED))
                }
            }

            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                log("내비 경로 상태: routing=${routeStateInfo.isRouting} 도착잔량=${routeStateInfo.remainDestinationChargingInt}")
                // isRouting is false during guidance on the emulator, so it does not end the route here;
                // onRouteCancelled / onDestinationArrived do. (onRouteEnded is the deprecated name of
                // onRoutingCompleted: the route search finished, not the guidance.)
                // 0 with no route is the field's default, so a 0 counts only while isRouting says a route
                // is active; a positive value is used either way (isRouting is false during guidance here).
                val raw = routeStateInfo.remainDestinationChargingInt
                if (raw > 0 || (raw == 0 && routeStateInfo.isRouting)) trySend(NaviSignal.ArrivalEstimate(raw, ArrivalUnit.UNSPECIFIED))
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
        val id = tripReplies.begin(TripReplyMatcher.Point(place.lat, place.lon, place.poiId))
        pendingTrip = id to answer
        var sent = false
        try {
            val result = runCatching { navi.requestTrip(TripRequest(place.lat, place.lon, place.poiId ?: "", place.name, place.poiSubId ?: "")) }
            if (result.isFailure) {
                log("경로 거리 요청 실패: ${result.exceptionOrNull()?.message}")
                return null
            }
            sent = true
            val info = withTimeoutOrNull(TRIP_TIMEOUT_MS) { answer.await() }
            // TripInfo.distance is in metres (navi-data KDoc).
            info?.distance?.takeIf { it > 0 }?.div(1000.0)
        } finally {
            // Also on cancellation: a request sent but not answered may still get a late reply.
            pendingTrip = null
            tripReplies.end(id, lateReplyPossible = sent && !answer.isCompleted, now = System.currentTimeMillis())
        }
    }

    private companion object {
        const val TRIP_TIMEOUT_MS = 6_000L
        const val DESTINATION_ASK_EVERY_MS = 5_000L
    }
}

/**
 * Pairs NaviHelper's onTripInfo replies with requestTrip calls. The reply carries no request id, so
 * after a request times out its reply may still arrive while the next request waits. Rules:
 * - No earlier request is still owed a reply: the reply answers the current request.
 * - One may be: the reply answers the current request only if its route's destination is the
 *   requested place; otherwise (or with no destination to compare) it is taken as the late reply and
 *   dropped. The cost of a wrong drop is one straight-line estimate instead of a road distance.
 * - No request waiting: the reply is dropped.
 * A late reply is expected only for [lateWindowMs] after its request gave up.
 */
class TripReplyMatcher(
    private val lateWindowMs: Long = 30_000L,
    /** About 300 m: the route's destination point can be snapped to the road next to the POI. */
    private val nearDeg: Double = 0.003,
) {
    data class Point(val lat: Double, val lon: Double, val poiId: String?)

    private var nextId = 1L
    private var current: Pair<Long, Point>? = null
    /** When each request that may still get a late reply stops being owed one. */
    private val lateUntil = ArrayDeque<Long>()

    @Synchronized
    fun begin(target: Point): Long {
        val id = nextId++
        current = id to target
        return id
    }

    @Synchronized
    fun end(id: Long, lateReplyPossible: Boolean, now: Long) {
        if (current?.first == id) current = null
        if (lateReplyPossible) lateUntil.addLast(now + lateWindowMs)
    }

    /** The id of the request [dest] answers, or null to drop the reply. */
    @Synchronized
    fun match(dest: Point?, now: Long): Long? {
        while (lateUntil.isNotEmpty() && lateUntil.first() < now) lateUntil.removeFirst()
        val (id, target) = current ?: run { lateUntil.removeFirstOrNull(); return null }
        if (lateUntil.isEmpty()) return id
        if (dest != null && same(dest, target)) return id
        lateUntil.removeFirst()
        return null
    }

    private fun same(a: Point, b: Point): Boolean {
        if (!a.poiId.isNullOrEmpty() && a.poiId == b.poiId) return true
        return kotlin.math.abs(a.lat - b.lat) <= nearDeg && kotlin.math.abs(a.lon - b.lon) <= nearDeg
    }
}

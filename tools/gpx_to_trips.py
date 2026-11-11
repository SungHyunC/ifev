#!/usr/bin/env python3
"""
폰 GPS 기록(GPX 또는 CSV)을 이프EV 주행 기록(trips.json)으로 바꾼다. 표준 라이브러리만 쓴다.

좌표는 결과에 남기지 않는다. 주행마다 시작·끝 시각, 거리, 기온(서울 월 평년값), 공조 비율(가정),
연비(입력값), 출발·도착이 집/회사/기타인지만 남긴다. 형식은 앱 저장소(Store)의 trips.json과 같다.

    python3 tools/gpx_to_trips.py 기록폴더/ --out drives/A/trips.json --fuel 12.4
    python3 tools/gpx_to_trips.py a.gpx b.gpx --home 37.55,126.91 --office 37.50,127.03 --out trips.json

- 집·회사를 주지 않으면 기록에서 추정한다: 밤(19~7시)에 가장 자주 끝난 곳이 집, 평일 낮에 가장 자주
  끝난 다른 곳이 회사. 추정 좌표는 화면에만 (100m 단위로 흐려서) 보여 주고 저장하지 않는다.
- 주행 나누기: 기록이 5분 넘게 끊기거나, 5분 넘게 150m 안에 머물면 주차로 본다. 0.3km 미만은 버린다.
- 시속 200km를 넘는 점프는 위치 오차로 보고 거리에서 뺀다.
"""
import argparse
import csv
import json
import math
import os
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone, timedelta

KST = timezone(timedelta(hours=9))
SEOUL_MEAN_C = [-2.0, 0.7, 6.1, 12.6, 18.2, 22.7, 25.3, 26.1, 21.6, 15.0, 7.5, 0.2]
GAP_S = 5 * 60
STAY_S = 5 * 60
STAY_M = 150
MIN_TRIP_KM = 0.3
MAX_KMH = 200
PLACE_M = 300


def haversine_m(a, b):
    r = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def parse_time(s):
    s = s.strip().replace("Z", "+00:00")
    try:
        t = datetime.fromisoformat(s)
    except ValueError:
        t = datetime.fromtimestamp(float(s) / (1000 if float(s) > 1e11 else 1), tz=timezone.utc)
    if t.tzinfo is None:
        t = t.replace(tzinfo=KST)
    return t


def read_gpx(path):
    pts = []
    for el in ET.parse(path).iter():
        if el.tag.endswith("trkpt") or el.tag.endswith("rtept"):
            t = next((c.text for c in el if c.tag.endswith("time")), None)
            if t:
                pts.append((parse_time(t), float(el.get("lat")), float(el.get("lon"))))
    return pts


def read_csv(path):
    pts = []
    with open(path, newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            low = {k.lower().strip(): v for k, v in row.items() if k}
            t = low.get("time") or low.get("timestamp") or low.get("date time") or low.get("datetime")
            lat = low.get("lat") or low.get("latitude")
            lon = low.get("lon") or low.get("lng") or low.get("longitude")
            if t and lat and lon:
                pts.append((parse_time(t), float(lat), float(lon)))
    return pts


def load(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            files += [os.path.join(p, f) for f in sorted(os.listdir(p)) if f.lower().endswith((".gpx", ".csv"))]
        else:
            files.append(p)
    pts = []
    for f in files:
        pts += read_gpx(f) if f.lower().endswith(".gpx") else read_csv(f)
    pts.sort(key=lambda p: p[0])
    return pts, files


def split_trips(pts):
    """Lists of points, one per drive."""
    trips, cur = [], []
    anchor = None  # (time, point) where a possible stay began
    for p in pts:
        if cur and (p[0] - cur[-1][0]).total_seconds() > GAP_S:
            trips.append(cur)
            cur, anchor = [], None
        if anchor and haversine_m(anchor[1][1:], p[1:]) <= STAY_M:
            if (p[0] - anchor[0]).total_seconds() > STAY_S:
                # Parked: cut at the moment the stay began.
                idx = next(i for i, q in enumerate(cur) if q[0] >= anchor[0])
                trips.append(cur[: idx + 1])
                cur = [p]
                anchor = (p[0], p)
                continue
        else:
            anchor = (p[0], p)
        cur.append(p)
    if cur:
        trips.append(cur)
    return trips


def distance_km(points):
    total = 0.0
    for a, b in zip(points, points[1:]):
        d = haversine_m(a[1:], b[1:])
        dt = max((b[0] - a[0]).total_seconds(), 1)
        if d / dt * 3.6 <= MAX_KMH:
            total += d
    return total / 1000


def cluster_top(points, radius=200):
    """Most frequent location among points, by greedy clustering."""
    clusters = []
    for p in points:
        for c in clusters:
            if haversine_m(c["center"], p) <= radius:
                c["n"] += 1
                break
        else:
            clusters.append({"center": p, "n": 1})
    return max(clusters, key=lambda c: c["n"])["center"] if clusters else None


def guess_places(trips):
    ends_night = [t[-1][1:] for t in trips if t[-1][0].astimezone(KST).hour >= 19 or t[-1][0].astimezone(KST).hour < 7]
    home = cluster_top(ends_night)
    ends_day = [t[-1][1:] for t in trips
                if t[-1][0].astimezone(KST).weekday() < 5 and 7 <= t[-1][0].astimezone(KST).hour < 12
                and (home is None or haversine_m(home, t[-1][1:]) > PLACE_M)]
    office = cluster_top(ends_day)
    return home, office


def place(p, home, office):
    if home and haversine_m(home, p) <= PLACE_M:
        return "HOME"
    if office and haversine_m(office, p) <= PLACE_M:
        return "OFFICE"
    return "OTHER"


def blur(p):
    return None if p is None else f"{round(p[0], 3)}, {round(p[1], 3)} (100m 단위로 흐림)"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", help="GPX/CSV 파일 또는 폴더")
    ap.add_argument("--out", required=True, help="결과 trips.json 경로")
    ap.add_argument("--home", help="집 위도,경도 (없으면 추정)")
    ap.add_argument("--office", help="회사 위도,경도 (없으면 추정)")
    ap.add_argument("--fuel", type=float, help="지금 차의 평균 연비 km/L (계기판 값)")
    ap.add_argument("--climate", type=float, default=1.0, help="공조를 켠 비율 0~1 (기본 1, 모르면 그대로)")
    a = ap.parse_args()

    pts, files = load(a.inputs)
    if len(pts) < 2:
        sys.exit("GPS 점이 없습니다: " + ", ".join(files))
    trips = [t for t in split_trips(pts) if len(t) >= 2]
    home = tuple(map(float, a.home.split(","))) if a.home else None
    office = tuple(map(float, a.office.split(","))) if a.office else None
    if home is None or office is None:
        gh, go = guess_places(trips)
        home, office = home or gh, office or go

    out = []
    for t in trips:
        km = distance_km(t)
        if km < MIN_TRIP_KM:
            continue
        start, end = t[0][0], t[-1][0]
        out.append({
            "s": int(start.timestamp() * 1000), "e": int(end.timestamp() * 1000), "km": round(km, 3),
            "from": place(t[0][1:], home, office), "to": place(t[-1][1:], home, office),
            "t": SEOUL_MEAN_C[start.astimezone(KST).month - 1], "c": a.climate,
            "f": a.fuel, "ds": "TRACK",
        })
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)

    days = sorted({datetime.fromtimestamp(x["s"] / 1000, KST).date() for x in out})
    span = (days[-1] - days[0]).days + 1 if days else 0
    tags = Counter((x["from"], x["to"]) for x in out)
    print(f"파일 {len(files)}개, GPS 점 {len(pts):,}개 → 주행 {len(out)}번, {sum(x['km'] for x in out):,.1f} km")
    print(f"기간 {span}일 (운전한 날 {len(days)}일)")
    print(f"집 {blur(home)} / 회사 {blur(office)}")
    print("출발→도착: " + ", ".join(f"{k[0]}→{k[1]} {v}" for k, v in tags.most_common(6)))
    print(f"저장: {a.out} (좌표 없음)")


if __name__ == "__main__":
    main()

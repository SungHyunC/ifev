#!/usr/bin/env python3
"""
공개 데이터셋 VED(Vehicle Energy Dataset)의 주행 기록 한 대분을 이프EV 주행 기록(trips.json)으로 바꾼다.
표준 라이브러리만 쓴다. 좌표는 결과에 남기지 않는다.

    python3 -I tools/ved_to_trips.py VED폴더1/ VED폴더2/ --veh 575 --from 2018-04-03 --days 28 \
        --out app/src/main/assets/public/ved-575/trips.json

전기차 검증용 주행별 행(좌표·시각 없음)을 뽑을 때(앱 단위 테스트 VedLeafCheckTest가 읽는다):

    python3 -I tools/ved_to_trips.py VED폴더1/ VED폴더2/ --ev-rows app/src/test/resources/ved_leaf_trips.json \
        --veh 10,455,541

입력은 VED 동적 데이터의 주간 CSV(VED_YYMMDD_week.csv)다. 파일 이름의 날짜로 기간에 걸치는 주만 읽는다.
VED: https://github.com/gsoh/VED (Apache-2.0). Oh, Leblanc, Peng, "Vehicle Energy Dataset (VED),
A Large-scale Dataset for Vehicle Energy Consumption Research", IEEE T-ITS, 2020.

변환 규칙
- 시각: VED의 DayNum(1 = 2017-11-01 0시, 미국 미시간 현지 시각)으로 현지 벽시계 시각을 만들고, 그 시각을
  그대로 한국 시각(KST)인 것처럼 적는다. 앱이 하루를 KST로 나누므로 이렇게 해야 출퇴근 시간대와 날짜가
  현지 생활 그대로 남는다. 실제 UTC 시각과는 13~14시간 다르다(서머타임 전환일도 따로 고치지 않는다).
- 거리: 차속을 시간으로 적분한다("ds": "SPEED"). 샘플 사이가 30초를 넘으면 그 구간은 버린다.
- 연비(km/L): Fuel Rate가 주행 시간의 80 %를 넘게 있으면 그것으로, 아니면 MAF(g/s ÷ 14.7 ÷ 745 g/L)가
  80 %를 넘게 있으면 그것으로 구한다. 값이 있는 구간의 거리 ÷ 그 구간 연료량이다. 둘 다 없으면 null.
- 기온: 그 주행의 OAT 평균. 차에 OAT가 없으면, 같은 날짜·같은 시에 OAT를 보고한 VED 차들의 중앙값
  (그 도시의 그 시각 날씨), 그것도 없으면 같은 날짜의 중앙값, 그것도 없으면 읽은 자료의 그 달 평균.
- 공조 비율: 히터·에어컨 전력 신호가 없는 차(대부분의 내연기관차)는 1.0(gpx_to_trips.py와 같은 기본값).
  있으면 신호가 있는 샘플 중 히터 또는 에어컨 전력이 0보다 큰 샘플의 비율.
- 장소: VED는 개인정보 보호를 위해 주행의 시작·끝을 임의 길이만큼 지우고(random-fogging), 처음과 마지막
  큰 교차로 바깥을 지우며(major intersections bounding), 앤아버 시내 밖을 지운다(geo-fencing). 그래서 끝점을 반경 500 m로 묶는다. 밤(19~7시)에
  가장 자주 끝난 묶음이 집(HOME), 평일 낮(6~15시)에 끝나고 다음 출발까지 4시간 넘게 머문 일이 가장 잦은
  다른 묶음이 회사(OFFICE, 2번 이상일 때만), 나머지는 기타(OTHER).
  같은 집이라도 날마다 다른 교차로나 시 경계 지점에서 끝나 좌표만으로는 집을 자주 놓치므로, 한 가지를
  더 쓴다(밤샘 규칙): 저녁(17시 이후)이나 밤에 끝나고 다음 날 이후 아침까지 6시간 넘게 서 있었으면 그
  주행의 도착과 다음 주행의 출발을 집으로 본다. 두 규칙이 각각 몇 번 쓰였는지 출력한다.
- 0.3 km 미만 주행은 버린다.
"""
import argparse
import csv
import json
import math
import os
import re
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
BASE = datetime(2017, 11, 1)  # DayNum 1
MAX_DT_S = 30.0
COVER = 0.8
AFR = 14.7
GASOLINE_G_PER_L = 745.0
MIN_TRIP_KM = 0.3
PLACE_M = 500
OFFICE_STAY_H = 4.0
OVERNIGHT_H = 6.0
OAT_MIN, OAT_MAX = -39.0, 50.0

COLS = {
    "day": "DayNum", "veh": "VehId", "trip": "Trip", "ts": "Timestamp(ms)",
    "lat": "Latitude[deg]", "lon": "Longitude[deg]", "speed": "Vehicle Speed[km/h]",
    "maf": "MAF[g/sec]", "oat": "OAT[DegC]", "fuel": "Fuel Rate[L/hr]",
    "ac_kw": "Air Conditioning Power[kW]", "ac_w": "Air Conditioning Power[Watts]", "heat_w": "Heater Power[Watts]",
}


def num(s):
    if not s or s == "NaN":
        return None
    try:
        v = float(s)
    except ValueError:
        return None
    return None if math.isnan(v) else v


def haversine_m(a, b):
    r = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def wall_clock(daynum):
    return BASE + timedelta(days=daynum - 1)


def week_files(inputs, start, end):
    """Weekly CSVs whose week (named by its first day) overlaps [start, end)."""
    files = []
    for p in inputs:
        names = [os.path.join(p, f) for f in sorted(os.listdir(p))] if os.path.isdir(p) else [p]
        for f in names:
            if not f.lower().endswith(".csv"):
                continue
            m = re.search(r"(\d{6})_week", os.path.basename(f))
            if m:
                w0 = datetime.strptime(m.group(1), "%y%m%d")
                if w0 + timedelta(days=8) < start or w0 > end:
                    continue
            files.append(f)
    return files


class TripAcc:
    def __init__(self, day):
        self.day = day
        self.last_ts = None
        self.max_ts = 0.0
        self.km = 0.0
        self.dur = 0.0
        self.fuel = [0.0, 0.0, 0.0]  # litres, seconds covered, km covered
        self.maf = [0.0, 0.0, 0.0]
        self.oat = []
        self.hvac_n = 0
        self.hvac_on = 0
        self.p0 = None
        self.p1 = None


def read(files, veh, start, end):
    trips = {}
    # (date, hour) -> vehicle -> [sum, n]; city weather from every car that reports OAT.
    city = defaultdict(lambda: defaultdict(lambda: [0.0, 0]))
    rows = 0
    for path in files:
        with open(path, newline="", encoding="utf-8-sig") as fh:
            r = csv.reader(fh)
            hdr = next(r)
            ix = {k: hdr.index(v) for k, v in COLS.items() if v in hdr}
            i_day, i_veh, i_trip, i_ts = ix["day"], ix["veh"], ix["trip"], ix["ts"]
            i_oat = ix.get("oat")
            for row in r:
                rows += 1
                oat = num(row[i_oat]) if i_oat is not None else None
                is_target = row[i_veh] == veh or (row[i_veh].endswith(".0") and row[i_veh][:-2] == veh)
                if oat is None and not is_target:
                    continue
                day = float(row[i_day])
                ts = float(row[i_ts]) / 1000.0
                t_wall = wall_clock(day) + timedelta(seconds=ts)
                if oat is not None and OAT_MIN <= oat <= OAT_MAX:
                    c = city[(t_wall.date(), t_wall.hour)][row[i_veh]]
                    c[0] += oat
                    c[1] += 1
                if not is_target:
                    continue
                t0 = wall_clock(day)
                if not (start <= t0 < end):
                    continue
                key = int(float(row[i_trip]))
                t = trips.get(key)
                if t is None:
                    t = trips[key] = TripAcc(day)
                dt = 0.0 if t.last_ts is None else ts - t.last_ts
                if dt < 0 or dt > MAX_DT_S:
                    dt = 0.0
                t.last_ts = ts
                t.max_ts = max(t.max_ts, ts)
                g = lambda k: num(row[ix[k]]) if k in ix else None
                sp = g("speed")
                dkm = 0.0
                if sp is not None:
                    dkm = sp * dt / 3600.0
                    t.km += dkm
                    t.dur += dt
                fr = g("fuel")
                if fr is not None:
                    t.fuel[0] += fr * dt / 3600.0
                    t.fuel[1] += dt
                    t.fuel[2] += dkm
                maf = g("maf")
                if maf is not None:
                    t.maf[0] += maf / AFR / GASOLINE_G_PER_L * dt
                    t.maf[1] += dt
                    t.maf[2] += dkm
                if oat is not None and OAT_MIN <= oat <= OAT_MAX:
                    t.oat.append(oat)
                heat, ac_w, ac_kw = g("heat_w"), g("ac_w"), g("ac_kw")
                if heat is not None or ac_w is not None or ac_kw is not None:
                    t.hvac_n += 1
                    if (heat or 0) > 0 or (ac_w or 0) > 0 or (ac_kw or 0) > 0:
                        t.hvac_on += 1
                la, lo = g("lat"), g("lon")
                if la is not None and lo is not None:
                    if t.p0 is None:
                        t.p0 = (la, lo)
                    t.p1 = (la, lo)
        print(f"  읽음: {os.path.basename(path)}", file=sys.stderr)
    return trips, city, rows


def city_temps(city):
    """Median across vehicles of each vehicle's mean OAT, per (date, hour), per date and per month."""
    by_hour, by_date, by_month = {}, defaultdict(list), defaultdict(list)
    for (d, h), cars in city.items():
        means = [s / n for s, n in cars.values()]
        by_hour[(d, h)] = statistics.median(means)
        by_date[d] += means
        by_month[(d.year, d.month)] += means
    return (by_hour, {d: statistics.median(v) for d, v in by_date.items()},
            {m: sum(v) / len(v) for m, v in by_month.items()})


def cluster(points):
    """Greedy clustering with running-mean centres. Returns centres and the cluster index of each point."""
    centres, counts, labels = [], [], []
    for p in points:
        best = None
        for i, c in enumerate(centres):
            d = haversine_m(c, p)
            if d <= PLACE_M and (best is None or d < best[0]):
                best = (d, i)
        if best is None:
            centres.append(p)
            counts.append(1)
            labels.append(len(centres) - 1)
        else:
            i = best[1]
            n = counts[i]
            centres[i] = ((centres[i][0] * n + p[0]) / (n + 1), (centres[i][1] * n + p[1]) / (n + 1))
            counts[i] = n + 1
            labels.append(i)
    return centres, labels


HV = ("HV Battery Current[A]", "HV Battery Voltage[V]")


def ev_rows(inputs, vehs, out_path):
    """Per-trip energy of EVs: km, sec, OAT, battery energy out (kWh), mean heater and A/C power.

    Battery energy is -∫ current × voltage dt (VED: negative current = discharge), regeneration included.
    Kept only when speed, battery and OAT readings each cover more than 80 % of the trip and km >= 1.
    """
    files = [os.path.join(p, f) for p in inputs for f in sorted(os.listdir(p)) if f.lower().endswith(".csv")] \
        if all(os.path.isdir(p) for p in inputs) else list(inputs)
    acc = {}
    for path in files:
        with open(path, newline="", encoding="utf-8-sig") as fh:
            r = csv.reader(fh)
            hdr = next(r)
            if not all(h in hdr for h in HV):
                continue
            ix = {k: hdr.index(v) for k, v in COLS.items() if v in hdr}
            i_cur, i_vol = hdr.index(HV[0]), hdr.index(HV[1])
            for row in r:
                v = row[ix["veh"]].removesuffix(".0")
                if v not in vehs:
                    continue
                key = (v, int(float(row[ix["trip"]])))
                ts = float(row[ix["ts"]]) / 1000.0
                t = acc.get(key)
                if t is None:
                    t = acc[key] = dict(last=None, max=0.0, km=0.0, dur=0.0, hvJ=0.0, hvS=0.0, oatS=0.0, oatSum=0.0,
                                        heatS=0.0, heatSum=0.0, acS=0.0, acSum=0.0)
                dt = 0.0 if t["last"] is None else ts - t["last"]
                if dt < 0 or dt > MAX_DT_S:
                    dt = 0.0
                t["last"] = ts
                t["max"] = max(t["max"], ts)
                g = lambda k: num(row[ix[k]]) if k in ix else None
                sp = g("speed")
                if sp is not None:
                    t["km"] += sp * dt / 3600.0
                    t["dur"] += dt
                cur, vol = num(row[i_cur]), num(row[i_vol])
                if cur is not None and vol is not None:
                    t["hvJ"] += cur * vol * dt
                    t["hvS"] += dt
                oat = g("oat")
                if oat is not None and OAT_MIN <= oat <= OAT_MAX:
                    t["oatSum"] += oat * dt
                    t["oatS"] += dt
                heat = g("heat_w")
                if heat is not None:
                    t["heatSum"] += heat * dt
                    t["heatS"] += dt
                ac = g("ac_w")
                if ac is None and g("ac_kw") is not None:
                    ac = g("ac_kw") * 1000
                if ac is not None:
                    t["acSum"] += ac * dt
                    t["acS"] += dt
        print(f"  읽음: {os.path.basename(path)}", file=sys.stderr)
    rows, dropped = [], Counter()
    for (v, trip), t in sorted(acc.items(), key=lambda kv: (int(kv[0][0]), kv[0][1])):
        dur = max(t["dur"], 1e-9)
        if t["km"] < 1:
            dropped["km<1"] += 1
        elif t["hvS"] / dur <= COVER:
            dropped["battery"] += 1
        elif t["oatS"] / dur <= COVER:
            dropped["oat"] += 1
        else:
            rows.append({
                "veh": int(v), "km": round(t["km"], 3), "sec": round(t["max"], 1),
                "oat": round(t["oatSum"] / t["oatS"], 1), "kwh": round(-t["hvJ"] / 3.6e6, 4),
                "heatW": round(t["heatSum"] / t["heatS"]) if t["heatS"] else None,
                "acW": round(t["acSum"] / t["acS"]) if t["acS"] else None,
            })
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write("[\n" + ",\n".join(json.dumps(x) for x in rows) + "\n]\n")
    print(f"CSV {len(files)}개 → 차 {sorted(vehs)}: 주행 {len(acc)}번 중 {len(rows)}번 저장 "
          f"(버림: " + ", ".join(f"{k} {n}" for k, n in dropped.items()) + ")")
    print(f"합계 {sum(x['km'] for x in rows):,.1f} km, {sum(x['kwh'] for x in rows):,.1f} kWh → 저장: {out_path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", help="VED 주간 CSV 파일 또는 폴더")
    ap.add_argument("--veh", required=True, help="VehId (--ev-rows에서는 쉼표로 여러 대)")
    ap.add_argument("--from", dest="start", help="시작 날짜 YYYY-MM-DD (현지)")
    ap.add_argument("--days", type=int, default=28, help="기간(일), 기본 28")
    ap.add_argument("--out", help="결과 trips.json 경로")
    ap.add_argument("--ev-rows", help="전기차 검증용 주행별 행을 이 경로에 쓴다(기간 없이 모든 CSV)")
    a = ap.parse_args()
    if a.ev_rows:
        ev_rows(a.inputs, set(a.veh.split(",")), a.ev_rows)
        return
    if not a.start or not a.out:
        ap.error("--from과 --out이 필요합니다")

    start = datetime.fromisoformat(a.start)
    end = start + timedelta(days=a.days)
    files = week_files(a.inputs, start, end)
    if not files:
        sys.exit("기간에 맞는 VED CSV가 없습니다")
    raw, city, rows = read(files, a.veh, start, end)
    by_hour, by_date, by_month = city_temps(city)

    items = []
    for key in sorted(raw, key=lambda k: raw[k].day):
        t = raw[key]
        if t.km < MIN_TRIP_KM:
            continue
        items.append((key, t))
    if not items:
        sys.exit(f"VehId {a.veh}: 기간 안에 {MIN_TRIP_KM} km 이상 주행이 없습니다")

    # Places from end points (start points too: the first trip of the day starts at home).
    ends = [(t.p1, wall_clock(t.day) + timedelta(seconds=t.max_ts)) for _, t in items]
    starts = [(t.p0, wall_clock(t.day)) for _, t in items]
    pts = [p for p, _ in ends] + [p for p, _ in starts]
    have = [p for p in pts if p is not None]
    centres, labels_have = cluster(have)
    it = iter(labels_have)
    labels = [next(it) if p is not None else None for p in pts]
    end_lab, start_lab = labels[: len(items)], labels[len(items):]

    night = Counter(end_lab[i] for i, (_, te) in enumerate(ends)
                    if end_lab[i] is not None and (te.hour >= 19 or te.hour < 7))
    home = night.most_common(1)[0][0] if night else None
    work = Counter()
    for i, (_, te) in enumerate(ends):
        if i + 1 >= len(items) or end_lab[i] is None or end_lab[i] == home:
            continue
        stay_h = (starts[i + 1][1] - te).total_seconds() / 3600
        if te.weekday() < 5 and 6 <= te.hour < 15 and stay_h >= OFFICE_STAY_H:
            work[end_lab[i]] += 1
    office = None
    if work:
        lab, n = work.most_common(1)[0]
        office = lab if n >= 2 else None

    def place(lab):
        return "HOME" if lab is not None and lab == home else "OFFICE" if lab is not None and lab == office else "OTHER"

    from_pl = [place(x) for x in start_lab]
    to_pl = [place(x) for x in end_lab]
    rule = Counter(cluster_home=sum(p == "HOME" for p in from_pl + to_pl))
    # Overnight rule: a car parked from the evening into a later morning slept at home.
    for i in range(len(items) - 1):
        te, ts_next = ends[i][1], starts[i + 1][1]
        if (te.hour >= 17 or te.hour < 7) and ts_next.date() > te.date() and \
                (ts_next - te).total_seconds() / 3600 >= OVERNIGHT_H:
            for pl, j in ((to_pl, i), (from_pl, i + 1)):
                if pl[j] != "HOME":
                    pl[j] = "HOME"
                    rule["overnight"] += 1

    out = []
    temp_src = Counter()
    fuel_src = Counter()
    climate_src = Counter()
    for i, (key, t) in enumerate(items):
        s_wall = wall_clock(t.day)
        e_wall = s_wall + timedelta(seconds=t.max_ts)
        if t.oat:
            temp, src = sum(t.oat) / len(t.oat), "trip"
        elif (s_wall.date(), s_wall.hour) in by_hour:
            temp, src = by_hour[(s_wall.date(), s_wall.hour)], "city_hour"
        elif s_wall.date() in by_date:
            temp, src = by_date[s_wall.date()], "city_date"
        elif (s_wall.year, s_wall.month) in by_month:
            temp, src = by_month[(s_wall.year, s_wall.month)], "city_month"
        else:
            sys.exit(f"주행 {key}: 기온을 구할 자료가 없습니다")
        temp_src[src] += 1
        f = None
        dur = max(t.dur, 1e-9)
        for name, (litres, secs, km) in (("fuel_rate", t.fuel), ("maf", t.maf)):
            if secs / dur > COVER and litres > 0 and km > 0:
                f = km / litres
                fuel_src[name] += 1
                break
        else:
            fuel_src["none"] += 1
        if t.hvac_n:
            c = t.hvac_on / t.hvac_n
            climate_src["signal"] += 1
        else:
            c = 1.0
            climate_src["default_1.0"] += 1
        # Local wall-clock time, written as if it were KST (see the module docstring).
        out.append({
            "s": int(s_wall.replace(tzinfo=KST).timestamp() * 1000),
            "e": int(e_wall.replace(tzinfo=KST).timestamp() * 1000),
            "km": round(t.km, 3),
            "from": from_pl[i], "to": to_pl[i],
            "t": round(temp, 1), "c": round(c, 2),
            "f": round(f, 2) if f else None, "ds": "SPEED",
        })

    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as fh:
        fh.write("[\n" + ",\n".join(json.dumps(x, ensure_ascii=False) for x in out) + "\n]\n")

    km = sum(x["km"] for x in out)
    days = sorted({datetime.fromtimestamp(x["s"] / 1000, KST).date() for x in out})
    fuel_km = sum(x["km"] for x in out if x["f"])
    fuel_l = sum(x["km"] / x["f"] for x in out if x["f"])
    places = Counter((x["from"], x["to"]) for x in out)
    print(f"CSV {len(files)}개, {rows:,}행 → VehId {a.veh}, {start.date()}부터 {a.days}일")
    print(f"주행 {len(out)}번, {km:,.1f} km, 운전한 날 {len(days)}일, 가장 긴 주행 {max(x['km'] for x in out):.1f} km")
    print(f"연비: " + ", ".join(f"{k} {v}" for k, v in fuel_src.items())
          + (f" → 평균 {fuel_km / fuel_l:.1f} km/L" if fuel_l else ""))
    print("기온 출처: " + ", ".join(f"{k} {v}" for k, v in temp_src.items())
          + f" (평균 {sum(x['t'] for x in out) / len(out):.1f}℃, 최저 {min(x['t'] for x in out):.1f}, 최고 {max(x['t'] for x in out):.1f})")
    print("공조 비율: " + ", ".join(f"{k} {v}" for k, v in climate_src.items()))
    print(f"장소 묶음 {len(centres)}개, 집 묶음 {'있음' if home is not None else '없음'}, 회사 묶음 {'있음' if office is not None else '없음'}; "
          f"집 표시: 좌표 묶음 {rule['cluster_home']}번 + 밤샘 규칙 {rule['overnight']}번")
    print("출발→도착: " + ", ".join(f"{k[0]}→{k[1]} {v}" for k, v in places.most_common(8)))
    print(f"저장: {a.out} (좌표 없음, 현지 시각을 KST로 적음)")


if __name__ == "__main__":
    main()

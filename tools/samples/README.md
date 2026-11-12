# 형식 견본 (지어낸 자료)

이 폴더의 `*_synthetic.json`은 **지어낸 형식 견본**입니다. 실제 사람의 위치 기록이 아니며, 좌표는 서울 안의 임의 값입니다.
`tools/gpx_to_trips.py`가 구글 지도 타임라인 형식을 읽는지 확인하는 데만 씁니다.

- `timeline_ondevice_synthetic.json`: 휴대폰에서 내보낸 Timeline.json(`semanticSegments`) 구조
- `semantic_history_synthetic.json`: 예전 구글 테이크아웃 월별 Semantic Location History(`timelineObjects`) 구조

```bash
python3 tools/gpx_to_trips.py tools/samples/timeline_ondevice_synthetic.json --out /tmp/t1.json
python3 tools/gpx_to_trips.py tools/samples/semantic_history_synthetic.json --out /tmp/t2.json
```

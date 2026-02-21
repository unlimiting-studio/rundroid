---
name: rundroid
description: 안드로이드 기기를 REST API로 원격 제어합니다. 스크린샷, UI 트리 조회, 탭, 스와이프, 텍스트 입력, 앱 관리 등을 수행할 수 있습니다.
---

# rundroid - 안드로이드 원격 제어

실물 안드로이드 기기를 REST API를 통해 원격 조작합니다.

## Base URL

환경변수 `RUNDROID_URL`에 서버 URL을 설정하세요.

```bash
export RUNDROID_URL="https://your-rundroid-server.workers.dev"
```

## 사전 조건

안드로이드 기기에 rundroid 앱이 설치되어 있고, WebSocket으로 서버에 연결된 상태여야 합니다.
연결 상태는 `GET /api/status`로 확인할 수 있습니다.
화면 해상도는 기기마다 다르므로, 좌표 기반 명령 사용 전에 스크린샷으로 확인하세요.

## 기본 사용 패턴

안드로이드 기기를 조작할 때는 다음 패턴을 따릅니다:

1. **스크린샷**으로 현재 화면 확인
2. **a11y-tree**로 UI 요소 위치/텍스트 파악
3. **tap/tap-a11y**로 요소 클릭
4. **type**으로 텍스트 입력
5. 결과 **스크린샷**으로 확인

## API 레퍼런스

모든 POST 요청은 `Content-Type: application/json` 헤더를 사용합니다.

### 상태 확인

```bash
curl -s $RUNDROID_URL/api/status
# {"connected": true}
```

### 스크린샷

PNG 이미지를 반환합니다. Read tool로 직접 볼 수 있습니다.

```bash
curl -s -X POST $RUNDROID_URL/api/screenshot \
  -o /tmp/screenshot.png --max-time 15
```

### Accessibility 트리

화면의 UI 요소 트리를 JSON으로 반환합니다. 각 노드에 className, text, contentDescription, bounds, clickable 등이 포함됩니다.

```bash
curl -s -X POST $RUNDROID_URL/api/a11y-tree --max-time 10
```

A11y 트리를 파싱하여 인터랙티브 요소만 추출하는 예:

```bash
curl -s -X POST $RUNDROID_URL/api/a11y-tree --max-time 10 \
  | python3 -c "
import json, sys
tree = json.load(sys.stdin)
def find(node, depth=0):
    text = node.get('text','')
    desc = node.get('contentDescription','')
    bounds = node.get('bounds',{})
    clickable = node.get('clickable', False)
    if text or desc or clickable:
        print(f\"{'  '*depth}text='{text}' desc='{desc}' clickable={clickable} bounds={bounds}\")
    for c in node.get('children',[]):
        find(c, depth+1)
find(tree.get('data', tree))
"
```

### 좌표 탭

```bash
curl -s -X POST $RUNDROID_URL/api/action/tap \
  -H "Content-Type: application/json" \
  -d '{"x": 540, "y": 630}'
```

### A11y 텍스트/경로 기반 탭

화면에서 텍스트 또는 경로로 UI 요소를 찾아 그 중심점을 탭합니다.

```bash
# 텍스트로 찾기
curl -s -X POST $RUNDROID_URL/api/action/tap-a11y \
  -H "Content-Type: application/json" \
  -d '{"text": "YouTube"}'

# 경로로 찾기 (세그먼트에 [text=...][desc=...][id=...][index=N] 조건 가능)
curl -s -X POST $RUNDROID_URL/api/action/tap-a11y \
  -H "Content-Type: application/json" \
  -d '{"path": "FrameLayout/LinearLayout[text=검색]/EditText"}'
```

### 스와이프

```bash
curl -s -X POST $RUNDROID_URL/api/action/swipe \
  -H "Content-Type: application/json" \
  -d '{"startX": 540, "startY": 1500, "endX": 540, "endY": 500, "duration": 300}'
```

- 위로 스크롤: startY > endY
- 아래로 스크롤: startY < endY
- duration: 밀리초 (300 권장)

### 네비게이션

```bash
# 뒤로가기
curl -s -X POST $RUNDROID_URL/api/action/back

# 홈
curl -s -X POST $RUNDROID_URL/api/action/home

# 최근 앱
curl -s -X POST $RUNDROID_URL/api/action/recent
```

### 텍스트 입력

현재 포커스된 입력 필드에 텍스트를 설정합니다. 먼저 입력 필드를 tap으로 포커스해야 합니다.

```bash
curl -s -X POST $RUNDROID_URL/api/action/type \
  -H "Content-Type: application/json" \
  -d '{"text": "hello rundroid"}'
```

### 입력 지우기

```bash
curl -s -X POST $RUNDROID_URL/api/action/clear-input
```

### 키 입력

Android KeyEvent 코드를 전송합니다. (셸 권한 제한 있음)

```bash
curl -s -X POST $RUNDROID_URL/api/action/key \
  -H "Content-Type: application/json" \
  -d '{"keyCode": 66}'
```

주요 KeyEvent 코드: 66(Enter), 67(Backspace), 4(Back), 3(Home)

### 앱 목록

```bash
curl -s $RUNDROID_URL/api/app/list
```

각 앱에 packageName, appName, versionName, isSystemApp이 포함됩니다.

### 앱 실행

```bash
curl -s -X POST $RUNDROID_URL/api/app/launch \
  -H "Content-Type: application/json" \
  -d '{"packageName": "com.android.chrome"}'
```

### 앱 종료

백그라운드 프로세스를 종료합니다. (완전 종료는 ADB 필요)

```bash
curl -s -X POST $RUNDROID_URL/api/app/stop \
  -H "Content-Type: application/json" \
  -d '{"packageName": "com.android.chrome"}'
```

### 앱 설치

URL에서 APK를 다운로드하여 설치합니다. (기기에서 사용자 확인 필요)

```bash
curl -s -X POST $RUNDROID_URL/api/app/install \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/app.apk"}'
```

### 앱 삭제

기기에서 삭제 확인 다이얼로그가 표시됩니다.

```bash
curl -s -X POST $RUNDROID_URL/api/app/uninstall \
  -H "Content-Type: application/json" \
  -d '{"packageName": "com.example.app"}'
```

## 응답 형식

### 성공 (JSON)
```json
{"requestId": "uuid", "success": true, "data": {"performed": true}}
```

### 실패
```json
{"requestId": "uuid", "success": false, "error": "에러 메시지"}
```

### 에러 HTTP 상태
- 400: 잘못된 요청 (파라미터 누락/타입 오류)
- 404: 없는 경로
- 503: 디바이스 미연결
- 504: 명령 타임아웃 (30초)

## 제약사항

- `app/stop`: 백그라운드 프로세스만 종료 가능 (full force-stop은 ADB 필요)
- `action/key`: 셸 권한 필요, 일반 앱에서는 제한적
- `app/install`, `app/uninstall`: 기기에서 사용자 확인 다이얼로그 표시
- 단일 디바이스만 지원
- 인증 없음 (네트워크 접근 가능한 누구나 사용 가능)

## 참고 문서

- [서버 배포 가이드](./SERVER_DEPLOY.md) - Cloudflare Workers 서버 설정 및 배포 방법

## 프로젝트 소스

- GitHub: https://github.com/unlimiting-studio/rundroid
- 서버: `server/` (Cloudflare Workers)
- 안드로이드: `android/` (Kotlin)

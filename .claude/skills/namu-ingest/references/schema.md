# 세계관 문서 규격

`world/` 아래 세계관 문서의 유일한 규격이다. 구조를 바꾸면 이 파일부터 고친다.

## 전체 구조

```
world/
├─ _todo.md          # 만들어야 할 항목, 확인 필요 (AI에게 보내지 않음)
├─ _sources.md       # 처리한 원문 기록 (AI에게 보내지 않음)
├─ base/             # 베이스: 질문마다 AI 프롬프트에 전체 포함
│   ├─ 01-overview.md
│   ├─ ...
│   └─ 10-glossary.md
└─ detail/           # 상세: 나중에 RAG로 검색. 1항목 = 1파일 = 1청크
    ├─ region/
    ├─ wing/
    ├─ association/
    ├─ office/
    ├─ syndicate/
    ├─ character/
    ├─ abnormality/
    ├─ distortion/
    ├─ technology/
    ├─ concept/
    ├─ event/
    ├─ work-setting/
    ├─ business/
    ├─ species/
    └─ item/
```

- 상세 문서의 폴더는 `type`과 1:1로 대응한다.
- 작품 구분은 폴더가 아니라 `works` 필드로 한다.
- `_`로 시작하는 파일은 관리용이며 AI에게 보내지 않는다.
- 메타데이터(YAML 프런트매터)는 사람과 백엔드용이다. AI에게 보낼 때는 떼고 본문만 보낸다.

## 베이스

### 섹션

| 파일 | id | 내용 |
|---|---|---|
| `01-overview.md` | `base.overview` | 도시가 어떤 곳인지 요약 |
| `02-city-structure.md` | `base.city-structure` | 둥지, 뒷골목, 외곽, 구의 구분 |
| `03-rules.md` | `base.rules` | 머리와 금기 |
| `04-wings.md` | `base.wings` | 날개와 특이점이라는 개념 |
| `05-factions.md` | `base.factions` | 해결사, 협회, 사무소, 조직의 성격 |
| `06-body-tech.md` | `base.body-tech` | 의체, 신체 개조 |
| `07-supernatural.md` | `base.supernatural` | 환상체, E.G.O, 뒤틀림의 개념 |
| `08-eras.md` | `base.eras` | 시대 구분 |
| `09-common-sense.md` | `base.common-sense` | 현실과 다르게 돌아가는 도시의 상식 |
| `10-glossary.md` | `base.glossary` | 공통 용어의 대표 표기와 한 줄 정의 |

### 베이스에 넣는 기준
네 가지를 모두 만족해야 한다.
1. 작품 공통이다.
2. 시대와 무관하다. (시대에 따라 달라지는 내용은 `08-eras.md`에만)
3. 도시 전반의 개념이다. 개별 대상(특정 날개, 특정 협회, 특정 인물)의 세부 사항은 넣지 않는다.
   - 예외(2026-11-22 사용자 확정): 판정에 자주 쓰이는 주요 집단(손가락 5개와 거미집, 해결사 협회 12개)과 특색은 이름과 한 줄 정체성만 `05-factions.md`에 둘 수 있다. 생존 여부, 소속 변화, 사건 경위처럼 시대에 따라 달라지는 정보는 여전히 넣지 않는다.
4. `certainty: official`이다.

베이스는 판정에 꼭 필요한 완전 기본 정보만 담는다. 여기에 더해 다음을 지킨다.
- 연도와 날짜는 쓰지 않는다. 시점은 시대 ID로만 나타낸다. 연도는 상세 항목에 둔다.
- `08-eras.md`에는 시대를 가르는 사건과, 그 시대에 판정이 달라지는 핵심 사실만 적는다. 개별 사건의 연대기는 상세의 event 항목에 둔다.
- 개별 인물, 개별 사건의 경위, 수치 같은 세부 사항은 넣지 않는다.

베이스의 내용은 상세 항목의 요약이다. 원본 사실은 상세 항목에 있고, 베이스 섹션의 `sources`가 그 상세 항목 ID를 가리킨다.

### 분량 상한
베이스 10개 섹션 본문 합계 **약 8,000자** (2026-11-21 사용자 확정, 메타데이터와 HTML 주석 제외).

분량은 반드시 Python의 `len()`처럼 글자 단위로 센다. `wc -m`은 이 환경의 로케일 설정 때문에 바이트를 세므로 쓰지 않는다.

```bash
python3 -c "import glob,re;print(sum(len('\n'.join(l for l in re.sub(r'^---\n.*?\n---\n','',open(f,encoding='utf-8').read(),flags=re.S).splitlines() if '<!--' not in l)) for f in glob.glob('world/base/*.md')))"
``` 넘을 것 같으면 덜 중요한 내용을 상세로 내리고 사용자에게 알린다.

### 메타데이터

```yaml
---
id: base.city-structure
order: 2
updated: 2026-11-21
sources: [region.nest, region.backstreets]
---
```

| 필드 | 의미 |
|---|---|
| `id` | 섹션 ID. 바꾸지 않는다 |
| `order` | 프롬프트에 넣는 순서. 순서를 고정해야 프롬프트 캐싱이 잘 된다 |
| `updated` | 마지막 수정일 |
| `sources` | 이 섹션의 근거가 되는 상세 항목 ID 목록 |

### 본문
- 짧은 단정형 문장이나 글머리표로 쓴다.
- 섹션 안의 소제목은 `##`부터 쓴다.

## 상세

### 메타데이터

```yaml
---
id: association.example
type: association
title: 예시 협회
aliases: [Example Association, 예시협회]
works: [ruina, limbus]
era: [all]
certainty: official
parent: null
related: [concept.example, region.example]
updated: 2026-11-21
---
```
(형식만 보여 주는 가짜 값이다.)

| 필드 | 필수 | 의미 | 쓰임 |
|---|---|---|---|
| `id` | 필수 | 항목의 고유 이름. 한 번 정하면 바꾸지 않는다 | 다른 항목에서 참조, 재임베딩 시 교체 기준 |
| `type` | 필수 | 항목 종류 (아래 목록) | 검색 범위 좁히기, 폴더 위치 |
| `title` | 필수 | 대표 표기. 본문에서는 이 표기만 쓴다 | 표기 통일, 검색 |
| `aliases` | 선택 | 다른 이름들: 영문명, 약칭, 별명, 다른 번역 표기 | 플레이어가 별칭으로 물어도 검색되게 함 |
| `works` | 필수 | 등장 작품 | 시나리오의 작품으로 검색 필터 |
| `era` | 필수 | 이 내용이 사실인 시점. 시대와 무관하면 `[all]` | 시나리오의 시점으로 검색 필터 |
| `certainty` | 필수 | `official` / `inferred` / `unknown` | AI가 근거의 확실성을 알게 함 |
| `parent` | 선택 | 긴 항목을 나눴을 때 원래 항목의 ID. 없으면 `null` | 하위 조각 검색 시 상위 요약도 가져옴 |
| `related` | 선택 | 연관된 다른 항목 ID 목록. 아직 없는 항목도 가능 | 확장 검색 |
| `updated` | 필수 | 마지막 수정일 (YYYY-MM-DD) | 바뀐 항목만 재임베딩 |

### `type`

| type | 뜻 |
|---|---|
| `region` | 지역: 각 구, 둥지, 뒷골목, 외곽 |
| `wing` | 날개(기업) |
| `association` | 해결사 협회 |
| `office` | 해결사 사무소 |
| `syndicate` | 조직: 뒷골목 조직 등 |
| `character` | 인물 |
| `abnormality` | 환상체 |
| `distortion` | 뒤틀림 |
| `technology` | 기술과 특이점, 의체 등 |
| `concept` | 개념: 머리, 금기, E.G.O 등 |
| `event` | 사건 |
| `work-setting` | 작품 고유의 제도나 장치 |
| `business` | 날개가 아닌 사업체: 식당, 보험사, 잡지, 연구소, 회사 등 |
| `species` | 종족: 인간, 혈귀, 외곽의 비인간 종족 등 |
| `item` | 고유한 물건: 유물, 이름 있는 고유 무기 등. 범용 기술·상품은 `technology` |

- 개별 대상이 아닌 "범주 자체"의 설명(예: 날개라는 개념, 환상체라는 개념, 유물이라는 개념)은 `concept`에 둔다. 개별 날개는 `wing`, 개별 환상체는 `abnormality`, 개별 유물은 `item`.

어디에도 맞지 않는 내용이 나오면 임의로 만들지 말고 사용자에게 새 type을 제안한다. 추가되면 이 표와 폴더를 함께 갱신한다.

### `works`

| 값 | 작품 |
|---|---|
| `lobotomy` | 로보토미 코퍼레이션 |
| `ruina` | 라이브러리 오브 루이나 |
| `limbus` | 림버스 컴퍼니 |
| `other` | 그 밖의 작품(웹툰, 소설 등). 자주 나오는 작품이 생기면 별도 값으로 등록한다 |

### `certainty`

| 값 | 의미 |
|---|---|
| `official` | 원작에서 명확히 나온 설정 |
| `inferred` | 원작 묘사로 미루어 볼 수 있지만 명시되지 않은 설정. 본문에 근거를 적는다 |
| `unknown` | 존재는 알려졌지만 내용이 공개되지 않은 설정. "알려지지 않았다"는 사실만 적고 내용을 지어내지 않는다 |

### ID와 파일 경로

- **형식:** `<type>.<slug>`
- **slug:** 공식 영문명이 있으면 그것을, 없으면 로마자 표기를 소문자 케밥 케이스로 쓴다. 예: `wing.l-corp`, `character.dante`
- **하위 항목:** `<부모 id>.<하위 slug>`. 시대별 하위 항목은 하위 slug로 시대 ID를 쓴다. 예: `wing.l-corp.history`, `wing.l-corp.lobotomy-era`
- **경로:** `world/detail/<type>/<slug와 하위 slug>.md`. 예: `wing.l-corp.history` → `world/detail/wing/l-corp.history.md`
- ID는 한 번 정하면 바꾸지 않는다. 제목이 바뀌어도 ID는 유지한다.

### 본문 템플릿

```markdown
## 요약
한두 문장.

## 판정 핵심 사실
- 예/아니오 판정에 바로 쓸 수 있는 단정형 사실을 한 줄에 하나씩.

## 상세
배경과 맥락. 지시어 대신 고유명사를 쓴다.

## 출처
- 나무위키 「문서명」 문서의 섹션명 (CC BY-NC-SA 2.0 KR)
- 원작 출처(작품, 칸토 등)를 알면 함께 적는다.
```

- 본문 길이는 약 300~800자. 넘으면 하위 항목으로 나눈다. 원문 정보가 적은 항목은 더 짧아도 된다.
- `상세`에 쓸 내용이 `판정 핵심 사실`과 겹치기만 하면 `상세` 섹션은 생략한다.
- `판정 핵심 사실`에는 추측을 넣지 않는다.
- `certainty: official` 항목 안에 일부 추정이 섞이면, 그 추정은 `상세`에만 "(추정: 근거)"를 붙여 쓴다.
- 날개·인물 등 아직 항목이 없는 대상에 관한 사실이 다른 주제의 예시로 나오면, 그 주제 항목 안에 예시로 둔다. 나중에 해당 대상의 항목이 생기면 옮긴다.

## 시대

### 규칙
- **세계 안의 시간만** 나타낸다.
- **경계는 사건으로 정의한다.** 각 시대는 "시작 사건부터 다음 시대의 시작 사건 전까지"이다. 그래서 공백기는 생기지 않고, 사이 기간은 앞 시대에 속한다.
- **답이 바뀌는 지점을 경계로 삼는다.** 시대는 적게 유지한다.
- **2단계 ID:** 대시대와, 필요할 때만 세부 구간. 예: `limbus-era`, `limbus-era.canto3`. `era: [limbus-era]`는 그 아래 세부 구간을 모두 포함한다.
- 시대와 무관한 설정은 `era: [all]`.
- 시대 정의와 시대별 변화는 `world/base/08-eras.md`에 둔다. 새 시대나 세부 구간이 필요해 보이면 임의로 만들지 말고 사용자에게 제안한다.

### 대시대 (확정된 ID)

| order | ID | 기준 |
|---|---|---|
| 1 | `pre-lobotomy` | 로보토미 코퍼레이션 이전의 과거사 (일단 하나로 둔다) |
| 2 | `lobotomy-era` | L사가 운영되던 시기 |
| 3 | `ruina-era` | 라이브러리 오브 루이나 시기 |
| 4 | `limbus-era` | 림버스 컴퍼니 시기 |

각 시대의 시작·끝 사건은 원문을 근거로 `08-eras.md`에 채운다.

## 관리 파일

### `world/_todo.md`
- **만들어야 할 항목:** `related`로 먼저 가리켰지만 아직 파일이 없는 항목. `id — 어디서 언급됐는지`
- **확인 필요:** 기존 내용과 충돌, 시대 판단이 애매한 것, 공식/해석이 애매한 것. 두 주장과 각각의 출처를 적는다.

### `world/_sources.md`
처리한 원문 기록. `날짜 | 나무위키 문서명 (섹션) | 영향받은 항목 ID`

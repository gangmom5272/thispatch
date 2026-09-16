# Steam 공지 게시일과 실제 패치 적용일 비교

검증일: 2026-09-15. 기존 PatchDateResolver, 분류 결과, ESTIMATED 날짜를 정답으로 사용하지 않았다.

## 결과

완료 사실과 적용 날짜를 원문에서 확인한 **15개 게임 137건 중 132건(96.4%)**에서 원문 적용 날짜와 Steam 게시일(UTC)의 달력 날짜가 같았다.

**이 비율은 선택된 날짜 명시 공지의 비교 결과이며, Steam 전체 공지의 정확도나 한국 시간 기준 정확도가 아니다.** 날짜를 명시한 LOTRO와 inZOI가 110건으로 표본 대부분을 차지한다. 날짜가 없는 완료 공지, 사전 예고, 사후 회고 전체의 비중을 측정한 무작위 표본이 아니다.

시간대를 원문에서 특정할 수 있는 완료 공지 14건은 게시 시각을 같은 시간대로 바꾸어 비교했고, 14건이 같은 날짜였다. 이 작은 부분집합도 무작위 표본은 아니다.

| 게임 | 비교 공지 | 원문 날짜 = 게시일(UTC) |
|---|---:|---:|
| The Lord of the Rings Online™ | 66 | 62 |
| inZOI | 44 | 44 |
| Toram Online | 4 | 4 |
| Halo Infinite | 4 | 4 |
| Monster Hunter: World | 4 | 4 |
| Conan Exiles Enhanced | 3 | 3 |
| NARUTO TO BORUTO: SHINOBI STRIKER | 3 | 3 |
| Limbus Company | 2 | 2 |
| Lies of P | 1 | 0 |
| EVE Online | 1 | 1 |
| World of Sea Battle | 1 | 1 |
| CosmicBreak Universal | 1 | 1 |
| World of Tanks Blitz | 1 | 1 |
| DJMAX RESPECT V | 1 | 1 |
| Dead Cells | 1 | 1 |

## 날짜가 다른 완료 공지

| 게임 / 공지 | 원문 적용일 | 게시일(UTC) | 날짜 차이 |
|---|---|---|---:|
| [The Lord of the Rings Online™: Update 45 Release Notes](https://store.steampowered.com/news/app/212500/view/1805431065509076) | 2025-07-23 | 2025-07-22 | -1 |
| [The Lord of the Rings Online™: Update 49.1 Release Notes](https://store.steampowered.com/news/app/212500/view/1839676055886478) | 2026-07-29 | 2026-07-30 | +1 |
| [The Lord of the Rings Online™: Update 30 Release Notes](https://store.steampowered.com/news/app/212500/view/4020014731795660526) | 2021-06-08 | 2021-09-09 | +93 |
| [The Lord of the Rings Online™: Update 37 Release Notes](https://store.steampowered.com/news/app/212500/view/5141476355658040554) | 2023-08-29 | 2023-08-31 | +2 |
| [Lies of P: Update Notes Version 1.5.0.0](https://store.steampowered.com/news/app/1627720/view/5750603429270969032) | 2024-02-13 | 2024-02-14 | +1 |

차이 = 게시일 − 원문 날짜. 시간대가 없는 ±1일 차이를 게시 지연으로 단정하지 않았다. LOTRO Update 30은 6월 8일 적용, 9월 9일 게시로 93일 차이가 난다. 원문에 완료형 표현이 있어도 과거 패치의 뒤늦은 게재일 수 있다.

## 사후 설명과 편집 사례

| 공지 | 언급된 적용일 | 최초 게시일(UTC) | 구분 |
|---|---|---|---|
| [Duet Night Abyss: Official Statement Concerning the March 18 External Malicious Attack](https://store.steampowered.com/news/app/3950020/view/1827626365752775) | 2026-03-18 | 2026-03-19 | retrospective |
| [CarX Street: 1.2.1 Update announcement](https://store.steampowered.com/news/app/1114150/view/1783872412112368) | 2024-11-22 | 2024-11-25 | retrospective_weekday_inference |
| [Monster Hunter: World: Performance Issue Occurring under Certain Conditions](https://store.steampowered.com/news/app/582010/view/2600199781642060080) | 2020-01-09 | 2020-01-10 | retrospective |
| [Monster Hunter: World: Notice Regarding Known Bugs](https://store.steampowered.com/news/app/582010/view/2600199781640622500) | 2020-01-09 | 2020-01-09 | retrospective |
| [STAR WARS™: The Old Republic™: Game Update 7.2.1b Patch Notes](https://store.steampowered.com/news/app/1286830/view/5133583099810485105) | 2023-04-27 | 2023-04-24 | later_edit_additional_patch |

CarX의 11월 22일은 원문 “On Friday”를 11월 25일 게시 시점의 직전 금요일로 해석한 날짜다. 다른 사례와 달리 전체 날짜를 직접 명시하지 않았다.

## 방법과 한계

- 대상 원자료: 390개 게임, 고유 공지 95,913건의 저장된 Steam API 응답. 같은 본문은 SHA-256으로 중복 제거했다.
- 검색 도구로 날짜와 완료 표현이 가까운 후보 1,339건을 찾았다. 후보 전부를 사람이 검수한 것은 아니다.
- LOTRO/inZOI 110건은 본문 도입부의 완료·적용 날짜 문장을 대조했다. 다른 게임 27건은 문맥을 확인해 날짜를 별도로 기록했다. 표본은 유의 선택이므로 신뢰구간이나 전체 정확도는 제시하지 않는다.
- 비교 단위는 공지 1건이다. 하나의 공지에 여러 패치가 있으면 주된 패치를 비교했고, 추후 추가 핫픽스는 별도 사례로 분리했다. 예: inZOI v0.8.2는 Windows 5월 2일, Mac은 5월 4일에 추가 반영되어 플랫폼별로 다르다.
- 연도 생략 공지 6건은 게시 연도로 보완했으며 year_inferred 필드에 표시했다. 월일 자체를 게시일에서 가져오지는 않았다.
- 완료 문구와 시각의 불일치도 존재한다. MHW 2019-10-29 글은 09:00 UTC 적용이라고 적었지만 08:40 UTC 게시다. Limbus 2026-02-12 글도 17:45 KST 적용보다 18분 앞서 최초 게시되었다. 같은 날짜여도 게시 순간 이미 적용되었다는 증거는 아니다. 수집 본문은 최초 게시 뒤 편집되었을 수도 있다.
- 제목 날짜만 비교한 2,743건의 탐색 결과는 버전 번호, 날짜 표기 순서, 예고 등이 섞여 있어 검증 수치에서 제외했다. dated-headings.json과 alignment-summary.json은 탐색 산출물이며 정답 집계가 아니다.
- 원문은 공식 공지의 진술이다. 실제 Steam 빌드 배포 로그까지 독립적으로 검증한 것은 아니다.

## 해석

완료된 패치를 알리는 공지는 게시일과 적용일이 같다는 가정을 지지하는 사례가 많다. 따라서 이런 유형의 게시일은 적용일의 유용한 근삿값 후보다. 패치를 언급하는 모든 글에 같은 가정을 적용할 근거는 부족하다. 사전 예고·사후 설명·추가 편집·플랫폼별 적용일은 구분해야 한다.

수치 재현 및 근거: reviewed-summary.json, reviewed-comparisons.json. 제품 코드 변경 없음.

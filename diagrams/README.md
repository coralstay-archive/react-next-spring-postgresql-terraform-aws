# diagrams/

Graphviz DOT 원본이다. `make diagrams`(= `python3 scripts/build-diagrams.py`)가 `docs/diagrams/<이름>.svg`로 렌더하고,
README·`docs/*.md`의 `<!--dot:<이름>-->` 블록을 `<img>`로 갱신한다. 렌더된 SVG는 생성물이라 직접 고치지 않는다.
`make test`(repo_test)가 `--check`로 SVG가 원본과 같은지 확인한다(`dot`이 없으면 건너뛴다).
바이트 비교는 `render-env.txt`(커밋된 SVG를 만든 graphviz 버전·OS)와 같은 환경에서만 하고, 다르면 글자·문서 블록·파일 목록만 본다.
graphviz를 올려 다시 렌더했으면 `python3 scripts/build-diagrams.py --env > diagrams/render-env.txt`로 고정값도 바꿔 함께 커밋한다.

- 색은 아래 센티넬 hex로만 쓴다. 빌드가 CSS 변수로 바꾸고, SVG 안의 `<style>`이 라이트/다크(`prefers-color-scheme`) 값을 정한다.
- 각 파일 첫머리의 `// alt: …` 줄이 이미지 대체 텍스트가 된다.
- 시퀀스 다이어그램처럼 DOT으로 표현할 수 없는 것만 Markdown 안에 mermaid로 쓴다(예: `docs/api.md`의 OAuth 로그인 흐름).

| 센티넬 | 토큰 | 용도 |
|---|---|---|
| `#f00001` | ink | 글자 |
| `#f00002` | muted | 보조 글자 |
| `#f00003` | line | 선·테두리 |
| `#f00004` | surface | 노드 바탕 |
| `#f00005` | surface-2 | 클러스터 바탕 |
| `#f00006` / `#f00007` | accent / accent-bg | 강조(앱·주 흐름) |
| `#f00008` / `#f00009` | aws / aws-bg | AWS 관리형 서비스 |
| `#f0000a` / `#f0000b` | ext / ext-bg | 외부(IdP·GitHub) |
| `#f0000c` / `#f0000d` | data / data-bg | 저장소 |
| `#f0000e` | warn | 실패·롤백 경로 |
| `#f0000f` | bg | 그림 배경(빌드가 넣는다) |

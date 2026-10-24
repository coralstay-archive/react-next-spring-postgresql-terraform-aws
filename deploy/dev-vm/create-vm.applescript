-- UTM 에 Rocky 개발 VM 을 만든다 (TASK-64#1). scripts/vm.sh create(make vm-create)가 부른다 — 직접 부르지 않는다.
-- 처음 실행할 때 macOS 가 "UTM 제어 허용" 자동화 권한 창을 한 번 띄운다(B1). 관리자 권한은 쓰지 않는다.
-- 인자: 이름 메모리(MiB) CPU수 디스크(raw) seedISO(cloud-init)
-- Apple 가상화 백엔드, 네트워크는 NAT(shared). 디스크와 seed 는 둘 다 VM 안으로 복사해 넣는다(이동식 아님). cloud-init 은 레이블 cidata 로
-- seed 디스크를 찾는다.
-- 저장소 공유 디렉터리는 여기서 붙이지 않는다: UTM 은 샌드박스 앱이라 AppleScript 로 받은 경로(이동식 ISO·공유 디렉터리)의 접근 권한을
-- 남기지 못해 다음 시작이 "Cannot access resource" 로 실패한다(2026-10-24 실측, UTM 4.7.5). make test 는 rsync 로 저장소를 VM 에 맞추고,
-- 실시간 공유가 필요하면 UTM 창에서 직접 디렉터리를 고른다(README 로컬 개발 환경).
on run argv
	set vmName to item 1 of argv
	set memMiB to (item 2 of argv) as integer
	set cpuCount to (item 3 of argv) as integer
	set diskFile to POSIX file (item 4 of argv)
	set seedFile to POSIX file (item 5 of argv)
	tell application "UTM"
		set vm to make new virtual machine with properties {backend:apple, configuration:{name:vmName, memory:memMiB, cpu cores:cpuCount, drives:{{source:diskFile}, {source:seedFile}}, network interfaces:{{index:0, mode:shared}}}}
		return id of vm
	end tell
end run

-- OpenDisplay USB 桥一键建立（快捷指令版 v2）
-- 设计要点：
--   * 不用 System Events / 不 activate 任何 app —— 规避快捷指令里的
--     「自动化权限」拦截（error -1743，且经常静默失败）。Mac app 是否
--     在跑不影响建桥；override 会让它在运行时自动拨 127.0.0.1:9000。
--   * 设备检测用 grep -cw，不依赖 adb devices 输出里的制表符
--     （粘贴到快捷指令时制表符可能被转成空格）。
--   * 不依赖通知：结果以 return 文本返回 —— 在快捷指令末尾加一步
--     「显示结果」即可看到成功/失败原因。
property adbPath : "/opt/homebrew/bin/adb"

on run
	try
		-- 1. adb 存在性
		try
			do shell script "test -x " & adbPath
		on error
			error "找不到 adb（" & adbPath & "）。请先安装：brew install android-platform-tools"
		end try

		-- 2. 在线设备检测（grep -c 统计 "device" 状态行，offline/unauthorized 不算）
		set devCount to do shell script adbPath & " devices 2>/dev/null | grep -cw 'device$' || echo 0"
		if devCount is "0" then
			error "没检测到在线的安卓设备。请插好 USB 线，并在平板上允许 USB 调试。"
		end if

		-- 3. 重建桥
		try
			do shell script adbPath & " forward --remove tcp:9000 >/dev/null 2>&1 || true"
		end try
		do shell script adbPath & " forward tcp:9000 tcp:9000"

		-- 4. 校验
		set fwdList to do shell script adbPath & " forward --list 2>/dev/null || true"
		if fwdList does not contain "tcp:9000" then
			error "桥建立后校验失败，请重试或重启 adb（adb kill-server）。"
		end if

		return "✅ USB 桥已就绪（127.0.0.1:9000 → 平板）。Mac 端 OpenDisplay 会自动连上。"
	on error errMsg
		return "❌ 桥接失败：" & errMsg
	end try
end run

' AutoDial PC 静默启动脚本
' 使用此脚本启动 AutoDial-PC.exe，将完全隐藏控制台黑窗口
' 使用方法：把此 .vbs 和 AutoDial-PC.exe 放在同一目录，双击此脚本即可

Dim objShell
Dim strDir
Dim strExe

Set objShell = CreateObject("WScript.Shell")

' 获取当前脚本所在目录
strDir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
strExe = strDir & "\AutoDial-PC.exe"

' 第二个参数 0 = 隐藏窗口，第三个参数 False = 不等待进程结束
objShell.Run Chr(34) & strExe & Chr(34), 0, False

Set objShell = Nothing

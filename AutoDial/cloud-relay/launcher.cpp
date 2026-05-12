// AutoDial Cloud Relay Launcher
// 功能：找到同目录下的 node.exe，运行 server.js
// 编译：g++ launcher.cpp -o AutoDial-Cloud-Relay.exe -lgdi32 -lcomdlg32 -lshell32 -lole32 -luuid

#include <windows.h>
#include <string>
#include <fstream>

// 获取当前 EXE 所在目录
std::wstring getExeDir() {
    wchar_t path[MAX_PATH];
    GetModuleFileNameW(NULL, path, MAX_PATH);
    std::wstring fullPath(path);
    size_t pos = fullPath.find_last_of(L"\\/");
    return (pos != std::wstring::npos) ? fullPath.substr(0, pos) : L".";
}

// 显示消息框
void showMessage(const std::wstring& title, const std::wstring& msg) {
    MessageBoxW(NULL, msg.c_str(), title.c_str(), MB_OK | MB_ICONINFORMATION);
}

// 显示错误消息框
void showError(const std::wstring& msg) {
    MessageBoxW(NULL, msg.c_str(), L"错误", MB_OK | MB_ICONERROR);
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    std::wstring exeDir = getExeDir();
    
    // 检查 node.exe 是否存在
    std::wstring nodePath = exeDir + L"\\node.exe";
    std::wifstream test(nodePath);
    bool nodeExists = (test.is_open());
    test.close();
    
    if (!nodeExists) {
        // 尝试从网络下载 Node.js 便携版
        std::wstring msg = L"未找到 node.exe。\n\n请确保在以下目录中存在 node.exe 和 server.js：\n" + exeDir + L"\n\n您可以从以下地址下载 Node.js 便携版：\nhttps://nodejs.org/dist/v18.20.0/node-v18.20.0-win-x64.zip\n\n解压后将 node.exe 放在此程序同级目录即可。";
        showError(msg);
        return 1;
    }
    
    // 检查 server.js 是否存在
    std::wstring serverPath = exeDir + L"\\server.js";
    std::wifstream test2(serverPath);
    bool serverExists = (test2.is_open());
    test2.close();
    
    if (!serverExists) {
        showError(L"未找到 server.js！请确保 server.js 与此程序在同一目录。");
        return 1;
    }
    
    // 解析命令行参数（端口）
    std::wstring cmdLine = GetCommandLineW();
    std::wstring portArg = L"--port 35430";  // 默认端口
    
    // 启动 node.exe server.js
    std::wstring cmd = L"\"" + nodePath + L"\" \"" + serverPath + L"\" " + portArg;
    
    // 显示启动信息
    std::wstring info = L"正在启动 AutoDial 云中转服务器...\n\n服务器将监听端口：35430\n\n请确保防火墙已放行此端口。\n\n（关闭此窗口将停止服务器）";
    showMessage(L"AutoDial 云中转", info);
    
    // 启动服务器（阻塞运行）
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));
    
    std::wstring cmdline = cmd;
    if (!CreateProcessW(NULL, &cmdline[0], NULL, NULL, FALSE, 0, NULL, exeDir.c_str(), &si, &pi)) {
        DWORD err = GetLastError();
        showError(L"启动服务器失败！错误代码：" + std::to_wstring(err));
        return 1;
    }
    
    // 等待进程结束
    WaitForSingleObject(pi.hProcess, INFINITE);
    
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    
    return 0;
}

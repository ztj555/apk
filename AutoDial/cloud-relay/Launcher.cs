using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

// AutoDial Cloud Relay Launcher
// 功能：找到同目录下的 node.exe，运行 server.js
// 编译：csc /target:winexe /out:AutoDial-Cloud-Relay.exe Launcher.cs

class Launcher
{
    static void Main(string[] args)
    {
        string exeDir = AppDomain.CurrentDomain.BaseDirectory;
        
        // 检查 node.exe
        string nodePath = Path.Combine(exeDir, "node.exe");
        if (!File.Exists(nodePath))
        {
            MessageBox.Show(
                "未找到 node.exe！\n\n请确保以下文件与此程序在同一目录：\n" +
                "• node.exe\n• server.js\n\n" +
                "您可以从以下地址下载 Node.js 便携版：\n" +
                "https://nodejs.org/dist/v18.20.0/node-v18.20.0-win-x64.zip\n\n" +
                "解压后将 node.exe 放在此程序同级目录即可。",
                "错误",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error
            );
            return;
        }
        
        // 检查 server.js
        string serverPath = Path.Combine(exeDir, "server.js");
        if (!File.Exists(serverPath))
        {
            MessageBox.Show(
                "未找到 server.js！\n\n请确保 server.js 与此程序在同一目录。",
                "错误",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error
            );
            return;
        }
        
        // 解析端口参数
        string port = "35430";
        for (int i = 0; i < args.Length; i++)
        {
            if ((args[i] == "--port" || args[i] == "-p") && i + 1 < args.Length)
            {
                port = args[i + 1];
                break;
            }
        }
        
        // 显示启动信息
        MessageBox.Show(
            "正在启动 AutoDial 云中转服务器...\n\n" +
            "服务器将监听端口：" + port + "\n\n" +
            "请确保防火墙已放行此端口。\n" +
            "（关闭服务器窗口将停止服务）",
            "AutoDial 云中转",
            MessageBoxButtons.OK,
            MessageBoxIcon.Information
        );
        
        // 启动服务器
        ProcessStartInfo psi = new ProcessStartInfo();
        psi.FileName = nodePath;
        psi.Arguments = "\" + serverPath + "\" --port " + port;
        psi.WorkingDirectory = exeDir;
        psi.UseShellExecute = false;
        psi.CreateNoWindow = false;
        
        try
        {
            Process proc = Process.Start(psi);
            if (proc != null)
            {
                proc.WaitForExit();
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "启动服务器失败！\n\n" + ex.Message,
                "错误",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error
            );
        }
    }
}

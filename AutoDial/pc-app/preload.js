const { contextBridge, ipcRenderer } = require('electron');

// 向渲染进程暴露安全的 IPC 通信桥接
contextBridge.exposeInMainWorld('api', {
  // 异步调用主进程
  invoke: (channel, ...args) => ipcRenderer.invoke(channel, ...args),

  // 发送消息到主进程（不等待回复）
  send: (channel, ...args) => ipcRenderer.send(channel, ...args),

  // 监听主进程消息
  on: (channel, callback) => {
    const subscription = (event, ...args) => callback(...args);
    ipcRenderer.on(channel, subscription);
    return () => ipcRenderer.removeListener(channel, subscription);
  },

  // 一次性监听
  once: (channel, callback) => {
    ipcRenderer.once(channel, (event, ...args) => callback(...args));
  }
});

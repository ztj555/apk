/**
 * AutoDial 浏览器插件 - Popup
 * 显示连接状态
 */

document.addEventListener('DOMContentLoaded', () => {
  const statusDot = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  const localIP = document.getElementById('localIP');
  const pinCode = document.getElementById('pinCode');
  const phoneStatus = document.getElementById('phoneStatus');

  // 检查PC端状态
  function checkStatus() {
    fetch('http://127.0.0.1:35432/')
      .then(response => response.json())
      .then(data => {
        // PC端运行中
        statusDot.className = 'status-dot connected';
        statusText.textContent = '✓ 已连接电脑主程序';
        statusText.className = 'status-text connected';
        localIP.textContent = data.ip;
        pinCode.textContent = data.pin;

        const count = data.phoneCount || 0;
        if (count > 0) {
          const names = (data.phones || []).map(p => p.note || p.name).join(', ');
          phoneStatus.textContent = '✓ ' + count + '部手机 (' + names + ')';
          phoneStatus.style.color = '#2ECC71';
        } else {
          phoneStatus.textContent = '✗ 未连接';
          phoneStatus.style.color = '#E74C3C';
        }
      })
      .catch(err => {
        // PC端未运行
        statusDot.className = 'status-dot error';
        statusText.textContent = '✗ PC端未运行';
        statusText.className = 'status-text';
        statusText.style.color = '#E74C3C';
        localIP.textContent = '--';
        pinCode.textContent = '--';
        phoneStatus.textContent = '--';
      });
  }

  // 初始检查
  checkStatus();
  
  // 每3秒刷新状态
  setInterval(checkStatus, 3000);
});

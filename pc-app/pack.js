const https = require('https');
// 强制跳过 SSL 证书验证（公司网络/代理环境）
https.globalAgent.options.rejectUnauthorized = false;

const { packager } = require('@electron/packager');

const ELECTRON_CACHE = 'C:\\Users\\EDY\\AppData\\Local\\electron\\Cache';

async function build() {
  console.log('[Build] 开始打包 AutoDial PC...');
  try {
    const appPaths = await packager({
      dir: '.',
      name: 'AutoDial',
      platform: 'win32',
      arch: 'x64',
      out: 'dist-packager',
      overwrite: true,
      asar: true,
      electronVersion: '28.3.3',
      download: {
        cacheRoot: ELECTRON_CACHE,
        // 跳过 SHASUMS 校验，直接用本地缓存
        verifyChecksum: false,
        // 使用淘宝镜像
        mirrorOptions: {
          mirror: 'https://npmmirror.com/mirrors/electron/'
        }
      },
      ignore: [
        /^\/dist/,
        /^\/dist-packager/,
        /^\/build-output\.log/,
        /^\/build-local\.bat/,
        /^\/pack\.js/,
        /^\/\.npmrc/
      ]
    });
    console.log('[Build] 打包成功！输出目录:', appPaths);
    console.log('[Build] exe 路径:', appPaths[0] + '\\AutoDial.exe');
  } catch (err) {
    console.error('[Build] 打包失败:', err);
    process.exit(1);
  }
}

build();

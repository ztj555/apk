const { packager } = require('@electron/packager');

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
      electronZipDir: process.env.ELECTRON_CACHE || undefined,
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

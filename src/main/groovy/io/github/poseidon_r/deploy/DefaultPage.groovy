package io.github.poseidon_r.deploy

/**
 * 下载页 HTML 模板(三单引号,Groovy 不做插值,JS 里的 ${} 原样输出)。
 * 可用 upload.pageTemplate 指向自定义 html 覆盖。
 */
class DefaultPage {
    static final String HTML = '''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>App 下载</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px; }
  .card { background: #fff; border-radius: 24px; box-shadow: 0 20px 60px rgba(0,0,0,0.25); max-width: 420px; width: 100%; padding: 40px 32px; text-align: center; position: relative; min-height: 380px; }
  .logo img { width: 96px; height: 96px; border-radius: 22px; box-shadow: 0 8px 20px rgba(118, 75, 162, 0.3); margin-bottom: 16px; }
  .app-name { font-size: 28px; font-weight: 700; color: #1a1a2e; margin-bottom: 8px; }
  .version { font-size: 16px; color: #888; margin-bottom: 4px; }
  .date { font-size: 14px; color: #aaa; margin-bottom: 4px; }
  .last { margin-bottom: 28px; }
  .download-btn { display: block; width: 100%; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; text-decoration: none; padding: 16px; border-radius: 14px; font-size: 17px; font-weight: 600; margin-bottom: 24px; box-shadow: 0 8px 20px rgba(118, 75, 162, 0.4); }
  .download-btn:active { transform: scale(0.98); }
  .qr-section { padding-top: 20px; border-top: 1px solid #f0f0f0; }
  .qr-hint { font-size: 14px; color: #888; margin-bottom: 16px; }
  .qr-code { display: inline-block; padding: 12px; background: #fff; border: 1px solid #eee; border-radius: 12px; }
  .qr-code img { display: block; width: 200px; height: 200px; }
  .qr-label { margin-top: 14px; font-size: 14px; color: #555; font-weight: 500; }
  .history-section { margin-top: 24px; padding-top: 20px; border-top: 1px solid #f0f0f0; }
  .history-title { font-size: 15px; font-weight: 600; color: #555; margin-bottom: 12px; text-align: left; }
  .history-list { display: flex; flex-direction: column; gap: 8px; max-height: 260px; overflow-y: auto; }
  .history-item { display: flex; align-items: center; justify-content: space-between; padding: 10px 14px; background: #f8f8fc; border-radius: 10px; text-decoration: none; transition: background 0.15s; }
  .history-item:hover { background: #eef0f8; }
  .history-item-info { text-align: left; }
  .history-item-ver { font-size: 14px; font-weight: 600; color: #1a1a2e; }
  .history-item-meta { font-size: 12px; color: #999; margin-top: 2px; }
  .history-item-dl { font-size: 13px; color: #667eea; font-weight: 600; white-space: nowrap; }
  .history-empty { font-size: 13px; color: #aaa; text-align: center; padding: 12px 0; }
  .state-container { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; border-radius: 24px; padding: 40px; }
  .spinner { width: 48px; height: 48px; border: 4px solid #e8e8f0; border-top-color: #667eea; border-radius: 50%; animation: spin 0.8s linear infinite; margin-bottom: 20px; }
  @keyframes spin { to { transform: rotate(360deg); } }
  .state-text { font-size: 15px; color: #888; }
  .state-error-icon { font-size: 48px; margin-bottom: 16px; }
  .state-retry-btn { margin-top: 16px; padding: 10px 28px; background: #667eea; color: #fff; border: none; border-radius: 10px; font-size: 15px; cursor: pointer; }
  .hidden { display: none !important; }
</style>
</head>
<body>
  <div class="card" id="app">
    <div id="stateLoading" class="state-container">
      <div class="spinner"></div>
      <p class="state-text">正在加载应用信息...</p>
    </div>
    <div id="stateError" class="state-container hidden">
      <div class="state-error-icon">⚠️</div>
      <p class="state-text" id="errorMessage">加载失败，请检查网络后重试</p>
      <button class="state-retry-btn" onclick="location.reload()">重新加载</button>
    </div>
    <div id="appContent" class="hidden">
      <div class="logo"><img id="appIcon" src="icon_launcher.png" alt="App Icon"></div>
      <h1 class="app-name" id="appName">-</h1>
      <div class="version" id="appVersion">-</div>
      <div class="date" id="appDate">-</div>
      <div class="date last" id="appCommit">-</div>
      <a class="download-btn" id="downloadLink" href="apk/app-release.apk" download="app-release.apk">⬇ 下载 APK</a>
      <div class="qr-section">
        <div class="qr-hint">扫一扫，手机下载安装</div>
        <div class="qr-code"><img id="qrCode" src="" alt="扫码下载" /></div>
        <div class="qr-label" id="qrLabel">-</div>
      </div>
      <div class="history-section">
        <div class="history-title">历史版本</div>
        <div class="history-list" id="historyList"></div>
      </div>
    </div>
  </div>
<script>
(async function() {
  const VERSIONS_PATH = 'versions.json';
  const ICON_PATH = 'icon_launcher.png';
  const stateLoading = document.getElementById('stateLoading');
  const stateError   = document.getElementById('stateError');
  const errorMessage = document.getElementById('errorMessage');
  const appContent   = document.getElementById('appContent');
  const appIcon      = document.getElementById('appIcon');
  const appName      = document.getElementById('appName');
  const appVersion   = document.getElementById('appVersion');
  const appDate      = document.getElementById('appDate');
  const appCommit    = document.getElementById('appCommit');
  const downloadLink = document.getElementById('downloadLink');
  const qrCode      = document.getElementById('qrCode');
  const qrLabel     = document.getElementById('qrLabel');
  const historyList = document.getElementById('historyList');
  function showError(msg) {
    errorMessage.textContent = msg;
    stateLoading.classList.add('hidden');
    stateError.classList.remove('hidden');
  }
  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  }
  try {
    const resp = await fetch(VERSIONS_PATH);
    if (!resp.ok) throw new Error(`versions.json 加载失败 (HTTP ${resp.status})`);
    const data = await resp.json();
    const current = data.current || {};
    const allVersions = Array.isArray(data.versions) ? data.versions : [];
    // 历史列表不重复显示当前(最新)版本,只列历史归档(按版本号去重)
    const versions = allVersions.filter((v) => v.versionName !== current.versionName);

    appName.textContent = current.appName || 'App';
    appVersion.textContent = current.versionName
      ? `v${current.versionName}` + (current.versionCode ? ` (build ${current.versionCode})` : '')
      : '-';
    if (current.buildTime) {
      appDate.textContent = `构建时间: ${current.buildTime}`;
    }
    if (current.branch || current.commit) {
      appCommit.textContent = [current.branch, current.commit].filter(Boolean).join(' @ ');
    }
    appIcon.src = ICON_PATH;
    const pageUrl = window.location.href;
    const baseUrl = pageUrl.substring(0, pageUrl.lastIndexOf('/') + 1);
    const apkPath = current.apkPath || 'apk/app-release.apk';
    downloadLink.href = apkPath;
    const apkUrl = baseUrl + apkPath;
    qrCode.src = `https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=${encodeURIComponent(apkUrl)}`;
    qrLabel.textContent = `${appName.textContent} ${appVersion.textContent}`;

    if (versions.length === 0) {
      historyList.innerHTML = '<div class="history-empty">暂无历史版本</div>';
    } else {
      historyList.innerHTML = versions.map((v) => {
        const ver = v.versionName ? `v${v.versionName}` : '-';
        const meta = [v.buildTime, v.commit].filter(Boolean).join(' · ');
        const path = v.apkPath || '#';
        return `<a class="history-item" href="${escapeHtml(path)}" download="">
          <div class="history-item-info">
            <div class="history-item-ver">${escapeHtml(ver)}</div>
            <div class="history-item-meta">${escapeHtml(meta)}</div>
          </div>
          <div class="history-item-dl">下载</div>
        </a>`;
      }).join('');
    }

    stateLoading.classList.add('hidden');
    appContent.classList.remove('hidden');
  } catch (err) {
    console.error(err);
    showError(err.message || '加载失败，请检查网络后重试');
  }
})();
</script>
</body>
</html>'''
}

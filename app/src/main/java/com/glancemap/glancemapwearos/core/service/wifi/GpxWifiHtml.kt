package com.glancemap.glancemapwearos.core.service.wifi

import org.json.JSONObject

object GpxWifiHtml {
    fun page(token: String): String =
        """
        <!doctype html>
        <html lang="zh-CN">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>GlanceMap GPX 上传</title>
        <style>
        body{font-family:system-ui,-apple-system,sans-serif;margin:24px;background:#101418;color:#eef2f5}
        h1{font-size:20px}input,button{font-size:16px;margin:8px 0;width:100%;padding:12px;box-sizing:border-box}
        button{background:#2e7d32;color:#fff;border:0;border-radius:8px}
        #status{white-space:pre-wrap;margin-top:12px;color:#9fb3c8}
        </style>
        </head>
        <body>
        <h1>GlanceMap GPX 上传</h1>
        <p>选择 GPX 文件后上传到手表。手机和手表需在同一 Wi-Fi。</p>
        <input id="file" type="file" accept=".gpx,application/gpx+xml,application/octet-stream">
        <button onclick="upload()">上传到手表</button>
        <div id="status"></div>
        <script>
        const token = ${JSONObject.quote(token)};
        function upload() {
          const f = document.getElementById('file').files[0];
          const s = document.getElementById('status');
          if (!f) { s.textContent = '请先选择 GPX 文件'; return; }
          if (!/\.gpx$/i.test(f.name)) { s.textContent = '只支持 .gpx 文件'; return; }
          const xhr = new XMLHttpRequest();
          xhr.open('POST', '/upload?t=' + encodeURIComponent(token) + '&name=' + encodeURIComponent(f.name));
          xhr.setRequestHeader('Content-Type', 'application/gpx+xml');
          xhr.upload.onprogress = e => {
            if (e.lengthComputable) s.textContent = '上传中 ' + Math.round(e.loaded / e.total * 100) + '%';
          };
          xhr.onload = () => {
            s.textContent = xhr.status === 200 ? '已保存到手表：' + f.name : '失败：' + xhr.responseText;
          };
          xhr.onerror = () => { s.textContent = '连接失败，请确认同一 Wi-Fi'; };
          xhr.send(f);
        }
        </script>
        </body>
        </html>
        """.trimIndent()
}

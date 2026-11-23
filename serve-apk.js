// Minimal static file server for sharing the Digi X TV APK.
// Serves the release directory, with a download landing page at "/".
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = 8080;
const ROOT = "C:\\Users\\vivek\\Downloads\\CC\\stremio-tv\\release";
const APK_NAME = "DigiX-TV-v2.1.0-b9.apk";

function listApks() {
  try {
    return fs.readdirSync(ROOT).filter((f) => f.toLowerCase().endsWith(".apk"));
  } catch {
    return [];
  }
}

const server = http.createServer((req, res) => {
  const urlPath = decodeURIComponent(req.url.split("?")[0]);

  // Landing page
  if (urlPath === "/" || urlPath === "/index.html") {
    const apks = listApks();
    const rows = apks
      .map((name) => {
        const size = (fs.statSync(path.join(ROOT, name)).size / (1024 * 1024)).toFixed(2);
        return `<li><a href="/${encodeURIComponent(name)}">${name}</a> <span class="s">${size} MB</span></li>`;
      })
      .join("");
    const html = `<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Digi X TV - Download</title>
<style>
 body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#0c0c10;color:#eee;
      margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:24px}
 .card{background:#18181c;border-radius:14px;padding:28px 32px;max-width:520px;width:100%;
       box-shadow:0 12px 40px rgba(0,0,0,.55)}
 h1{margin:0 0 4px;font-size:22px;letter-spacing:.4px}
 .sub{color:#8a8a92;font-size:13px;margin-bottom:22px}
 ul{list-style:none;padding:0;margin:0}
 li{background:#202027;border-radius:10px;padding:14px 16px;margin-bottom:10px;
    display:flex;justify-content:space-between;align-items:center;gap:12px}
 a{color:#7c3aed;text-decoration:none;font-weight:600;word-break:break-all}
 a:hover{text-decoration:underline}
 .s{color:#8a8a92;font-size:12px;white-space:nowrap}
 .note{color:#8a8a92;font-size:12px;margin-top:18px;line-height:1.6}
 .by{color:#22d3ee;font-size:12px;margin-top:10px;font-weight:600}
</style></head><body><div class="card">
<h1>Digi X TV</h1>
<div class="sub">v2.1.0 (versionCode 9) &middot; Android TV &amp; Android</div>
<ul>${rows || "<li>No APK found</li>"}</ul>
<div class="note">Tap the file to download, then open it to install.
You may need to allow "Install unknown apps" for your browser.</div>
<div class="by">Developed by Digi</div>
</div></body></html>`;
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(html);
    return;
  }

  // APK download
  const name = path.basename(urlPath);
  const file = path.join(ROOT, name);
  if (!file.startsWith(ROOT) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("Not found");
    return;
  }

  const stat = fs.statSync(file);
  const headers = {
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": stat.size,
    "Content-Disposition": `attachment; filename="${name}"`,
    "Accept-Ranges": "bytes",
  };

  // Range support so phone browsers can resume a 45 MB download
  const range = req.headers.range;
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    if (m) {
      const start = m[1] ? parseInt(m[1], 10) : 0;
      const end = m[2] ? parseInt(m[2], 10) : stat.size - 1;
      if (start >= stat.size) {
        res.writeHead(416, { "Content-Range": `bytes */${stat.size}` });
        res.end();
        return;
      }
      res.writeHead(206, {
        ...headers,
        "Content-Length": end - start + 1,
        "Content-Range": `bytes ${start}-${end}/${stat.size}`,
      });
      fs.createReadStream(file, { start, end }).pipe(res);
      return;
    }
  }

  res.writeHead(200, headers);
  fs.createReadStream(file).pipe(res);
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`serving ${ROOT}`);
  console.log(`listening on http://0.0.0.0:${PORT}`);
  listApks().forEach((f) => {
    const mb = (fs.statSync(path.join(ROOT, f)).size / (1024 * 1024)).toFixed(2);
    console.log(`  /${f}  (${mb} MB)`);
  });
});

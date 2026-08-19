package com.airmusic.player.transfer;

import android.util.Log;

import com.airmusic.player.library.AudioExt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny embedded HTTP server that lets a phone on the same LAN upload music
 * files to this device by scanning a QR code or typing the IP:port into a
 * browser. Only the app's supported audio extensions are accepted.
 */
public final class MusicTransferServer {

    private static final String TAG = "MusicTransferServer";
    private static final int DEFAULT_PORT = 8080;

    public interface Listener {
        void onUploaded(String fileName, boolean success, String message);
    }

    private final String musicFolderPath;
    private final byte[] iconPng;
    private final String language;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private int port = DEFAULT_PORT;

    public MusicTransferServer(String musicFolderPath, byte[] iconPng, String language,
                               Listener listener) {
        this.musicFolderPath = musicFolderPath;
        this.iconPng = iconPng;
        this.language = language == null ? "zh" : language;
        this.listener = listener;
    }

    /** Starts listening; returns the actual port (0 on failure). */
    public synchronized int start() {
        if (running.get()) return port;
        int attemptPort = DEFAULT_PORT;
        while (attemptPort < DEFAULT_PORT + 20) {
            try {
                serverSocket = new ServerSocket(attemptPort);
                port = attemptPort;
                break;
            } catch (IOException e) {
                attemptPort++;
            }
        }
        if (serverSocket == null) {
            Log.e(TAG, "no free port");
            return 0;
        }
        running.set(true);
        executor = Executors.newCachedThreadPool();
        executor.execute(this::acceptLoop);
        Log.i(TAG, "transfer server listening on " + port);
        return port;
    }

    public synchronized void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "accept failed", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            long contentLength = -1;
            String fileName = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Long.parseLong(line.substring(15).trim());
                } else if (lower.startsWith("x-file-name:")) {
                    fileName = URLDecoder.decode(line.substring(13).trim(), "UTF-8");
                }
            }

            OutputStream out = socket.getOutputStream();
            if ("GET".equals(method)) {
                if ("/icon.png".equals(path)) {
                    serveIcon(out);
                } else {
                    servePage(out);
                }
            } else if ("POST".equals(method) && "/upload".equals(path)) {
                byte[] body = contentLength > 0
                        ? readBody(reader, (int) contentLength)
                        : new byte[0];
                handleUpload(fileName, body, out);
            } else {
                sendResponse(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleUpload(String fileName, byte[] body, OutputStream out) throws IOException {
        String cleanName = fileName == null ? "" : new File(fileName).getName();
        if (cleanName.isEmpty() || body.length == 0) {
            String msg = t(language, "文件名或内容为空", "File name or content is empty",
                    "ファイル名または内容が空です", "파일 이름 또는 내용이 비어 있습니다");
            sendJson(out, 400, "ERR:" + msg);
            if (listener != null) listener.onUploaded(cleanName, false, msg);
            return;
        }
        if (!AudioExt.isAudio(cleanName)) {
            String msg = t(language, "仅支持音乐文件：", "Music files only: ",
                    "音楽ファイルのみ：", "음악 파일만 지원: ") + AudioExt.supportedList();
            sendJson(out, 415, "ERR:" + msg);
            if (listener != null) listener.onUploaded(cleanName, false,
                    t(language, "不支持的文件格式", "Unsupported file format",
                            "未対応のファイル形式", "지원하지 않는 파일 형식"));
            return;
        }
        File dir = musicFolderPath == null || musicFolderPath.isEmpty()
                ? new File(android.os.Environment.getExternalStorageDirectory(), "Music")
                : new File(musicFolderPath);
        if (!dir.exists() && !dir.mkdirs()) {
            String msg = t(language, "无法创建音乐目录", "Cannot create the music folder",
                    "音楽フォルダを作成できません", "음악 폴더를 만들 수 없습니다");
            sendJson(out, 500, "ERR:" + msg);
            if (listener != null) listener.onUploaded(cleanName, false, msg);
            return;
        }
        File target = new File(dir, cleanName);
        // Same-name files overwrite the existing file.
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(body);
        }
        String msg = t(language, "上传成功：", "Upload succeeded: ",
                "アップロード成功：", "업로드 성공: ") + target.getName();
        sendJson(out, 200, "OK:" + msg);
        if (listener != null) listener.onUploaded(target.getName(), true,
                t(language, "上传成功", "Upload succeeded", "アップロード成功", "업로드 성공"));
    }

    private void servePage(OutputStream out) throws IOException {
        byte[] page = buildPage().getBytes(StandardCharsets.UTF_8);
        sendResponse(out, 200, "text/html; charset=utf-8", page);
    }

    private void serveIcon(OutputStream out) throws IOException {
        if (iconPng == null || iconPng.length == 0) {
            sendResponse(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        sendResponse(out, 200, "image/png", iconPng);
    }

    private static void sendJson(OutputStream out, int code, String message) throws IOException {
        sendResponse(out, code, "text/plain; charset=utf-8",
                message.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendResponse(OutputStream out, int code, String contentType, byte[] body)
            throws IOException {
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }

    private static byte[] readBody(BufferedReader reader, int length) throws IOException {
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int r = reader.read();
            if (r < 0) break;
            body[read++] = (byte) r;
        }
        return body;
    }

    private static String t(String lang, String zh, String en, String ja, String ko) {
        if ("en".equals(lang)) return en;
        if ("ja".equals(lang)) return ja;
        if ("ko".equals(lang)) return ko;
        return zh;
    }

    private String buildPage() {
        String lang = language == null ? "zh" : language;
        String title = t(lang, "BukaMusic 音乐传输", "BukaMusic Music Transfer",
                "BukaMusic 音楽転送", "BukaMusic 음악 전송");
        String h2 = t(lang, "上传音乐到设备", "Upload Music to Device",
                "音楽をデバイスにアップロード", "기기로 음악 업로드");
        String desc = t(lang,
                "选择或拖拽音乐文件到下方，确认后上传。仅支持音乐格式，其他文件会自动跳过。",
                "Select or drag music files below, then confirm to upload. Only music formats are supported; other files are skipped automatically.",
                "音楽ファイルを選択またはドラッグして、アップロードを確認してください。音楽形式のみ対応で、他のファイルは自動的にスキップされます。",
                "음악 파일을 선택하거나 아래로 끌어다 놓은 뒤 업로드를 확인하세요. 음악 형식만 지원되며 다른 파일은 자동으로 건너뜁니다.");
        String dropText = t(lang, "点击选择文件，或将文件拖拽到此处",
                "Click to choose files, or drag files here",
                "クリックしてファイルを選択、またはここにドラッグ",
                "클릭하여 파일 선택 또는 여기로 끌어다 놓기");
        String upload = t(lang, "上传", "Upload", "アップロード", "업로드");
        String hint = t(lang,
                "仅支持音乐格式，其他文件会被拒绝。上传完成后曲库自动刷新。",
                "Music formats only; other files are rejected. The library refreshes automatically after uploads.",
                "音楽形式のみ対応です。他のファイルは拒否されます。アップロード完了後にライブラリが自動更新されます。",
                "음악 형식만 지원되며 다른 파일은 거부됩니다. 업로드가 완료되면 라이브러리가 자동으로 새로고침됩니다.");
        String tplSelected = t(lang, "已选择 {1} 个文件", "{1} file(s) selected",
                "{1} 個のファイルを選択", "파일 {1}개 선택됨");
        String tplUnsupported = t(lang, "{1}：不支持的文件格式，已跳过", "{1}: unsupported file format, skipped",
                "{1}：未対応のファイル形式のためスキップしました", "{1}: 지원하지 않는 파일 형식이라 건너뜀");
        String tplAlready = t(lang, "{1}：已在列表中", "{1}: already in the list",
                "{1}：リストに既にあります", "{1}: 이미 목록에 있음");
        String tplAdded = t(lang, "已添加 {1} 个文件", "Added {1} file(s)",
                "{1} 個のファイルを追加しました", "파일 {1}개 추가됨");
        String selectFirst = t(lang, "请先选择文件", "Please select files first",
                "ファイルを選択してください", "먼저 파일을 선택하세요");
        String tplUploading = t(lang, "正在上传 ({1}/{2}) {3} {4}%", "Uploading ({1}/{2}) {3} {4}%",
                "アップロード中 ({1}/{2}) {3} {4}%", "업로드 중 ({1}/{2}) {3} {4}%");
        String uploadSucceeded = t(lang, "上传成功", "Upload succeeded",
                "アップロード成功", "업로드 성공");
        String uploadFailed = t(lang, "上传失败", "Upload failed",
                "アップロード失敗", "업로드 실패");
        String tplDone = t(lang, "全部完成：成功 {1}，失败 {2}", "All done: {1} succeeded, {2} failed",
                "すべて完了：成功 {1}、失敗 {2}", "모두 완료: 성공 {1}, 실패 {2}");

        String js = "var exts=['mp3','flac','m4a','m4b','aac','ogg','oga','opus','wav','ape','wma','aiff','aif'];"
                + "var queue=[],uploading=false;"
                + "var drop=document.getElementById('drop'),file=document.getElementById('file'),"
                + "btn=document.getElementById('btn'),filesEl=document.getElementById('files'),"
                + "status=document.getElementById('status'),hint=document.getElementById('hint'),"
                + "prog=document.getElementById('progress'),bar=document.getElementById('bar'),"
                + "ptxt=document.getElementById('ptxt'),toasts=document.getElementById('toasts');"
                + "function fmt(s){var a=Array.prototype.slice.call(arguments,1);"
                + "return s.replace(/\\{(\\d)\\}/g,function(m,n){return a[n-1]!==undefined?a[n-1]:m})}"
                + "function fmtSize(n){if(n<1024)return n+' B';if(n<1048576)return(n/1024).toFixed(1)+' KB';"
                + "return(n/1048576).toFixed(1)+' MB'}"
                + "function toast(msg,ok){var t=document.createElement('div');t.className='toast '+(ok?'ok':'err');"
                + "t.textContent=msg;toasts.appendChild(t);"
                + "setTimeout(function(){t.classList.add('hide');setTimeout(function(){t.remove()},350)},3200)}"
                + "function renderFiles(){filesEl.innerHTML='';"
                + "if(queue.length){filesEl.style.display='block'}else{filesEl.style.display='none'}"
                + "queue.forEach(function(item,i){"
                + "var row=document.createElement('div');row.className='file';"
                + "var nm=document.createElement('span');nm.className='nm';nm.textContent=item.name;nm.title=item.name;"
                + "var sz=document.createElement('span');sz.className='sz';sz.textContent=fmtSize(item.size);"
                + "var x=document.createElement('button');x.className='x';x.textContent='✕';"
                + "x.onclick=function(){if(uploading)return;queue.splice(i,1);renderFiles()};"
                + "row.appendChild(nm);row.appendChild(sz);row.appendChild(x);filesEl.appendChild(row)});"
                + "hint.textContent=queue.length?fmt('" + tplSelected + "',queue.length):''}"
                + "function addFiles(list){var added=0;"
                + "for(var i=0;i<list.length;i++){var f=list[i];"
                + "var ok=exts.indexOf(f.name.split('.').pop().toLowerCase())>=0;"
                + "if(!ok){toast(fmt('" + tplUnsupported + "',f.name),false);continue}"
                + "var dup=queue.some(function(q){return q.name===f.name&&q.size===f.size});"
                + "if(dup){toast(fmt('" + tplAlready + "',f.name),false);continue}"
                + "queue.push({name:f.name,size:f.size,file:f});added++}"
                + "renderFiles();if(added)toast(fmt('" + tplAdded + "',added),true)}"
                + "drop.onclick=function(){file.click()};"
                + "file.onchange=function(){addFiles(file.files);file.value=''};"
                + "drop.ondragover=function(e){e.preventDefault();drop.classList.add('hover')};"
                + "drop.ondragleave=function(){drop.classList.remove('hover')};"
                + "drop.ondrop=function(e){e.preventDefault();drop.classList.remove('hover');addFiles(e.dataTransfer.files)};"
                + "btn.onclick=function(){"
                + "if(!queue.length){toast('" + selectFirst + "',false);return}"
                + "uploading=true;btn.disabled=true;var total=queue.length,done=0,okCount=0,failCount=0;"
                + "prog.style.display='block';"
                + "function next(){"
                + "if(done>=total){uploading=false;btn.disabled=false;prog.style.display='none';bar.style.width='0%';"
                + "var msg=fmt('" + tplDone + "',okCount,failCount);"
                + "status.textContent=msg;status.className='status '+(failCount?'err':'');"
                + "toast(msg,failCount===0);"
                + "queue=[];renderFiles();return}"
                + "var item=queue[done];"
                + "var xhr=new XMLHttpRequest();"
                + "xhr.open('POST','/upload');"
                + "xhr.setRequestHeader('X-File-Name',encodeURIComponent(item.name));"
                + "xhr.upload.onprogress=function(e){if(e.lengthComputable){"
                + "var pct=Math.round(e.loaded/e.total*100);"
                + "bar.style.width=pct+'%';ptxt.textContent=fmt('" + tplUploading + "',done+1,total,item.name,pct)}};"
                + "xhr.onload=function(){var t=xhr.responseText||'';var ok=xhr.status===200&&t.indexOf('OK:')===0;"
                + "var msg=ok?t.substring(3):t.substring(4);"
                + "if(ok)okCount++;else failCount++;"
                + "toast(msg,ok);"
                + "done++;bar.style.width='0%';next()};"
                + "xhr.onerror=function(){failCount++;done++;toast(item.name+'："+ uploadFailed + "',false);"
                + "bar.style.width='0%';next()};"
                + "xhr.send(item.file)}"
                + "next()};";

        String css = "*{box-sizing:border-box;margin:0;padding:0}"
                + "html,body{height:100%}"
                + "body{font-family:-apple-system,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "background:linear-gradient(160deg,#16304F 0%,#081120 100%);min-height:100vh;"
                + "display:flex;align-items:center;justify-content:center;color:#fff;padding:28px 20px}"
                + ".top{position:fixed;top:0;left:0;padding:20px 24px;font-size:22px;font-weight:700;color:#4FC3F7;"
                + "text-shadow:0 2px 8px rgba(79,195,247,.35)}"
                + ".wrap{width:100%;max-width:560px;margin:auto}"
                + ".card{background:#12233D;border:1px solid rgba(79,195,247,.25);border-radius:16px;"
                + "padding:28px;box-shadow:0 8px 28px rgba(0,0,0,.35)}"
                + "h2{font-size:18px;margin-bottom:6px;color:#81D4FA}"
                + "p{color:#A8C3E0;font-size:14px;line-height:1.7;margin-bottom:18px}"
                + ".drop{border:2px dashed rgba(79,195,247,.6);border-radius:12px;padding:34px 16px;"
                + "text-align:center;color:#A8C3E0;font-size:15px;cursor:pointer;transition:.2s;background:rgba(79,195,247,.05)}"
                + ".drop.hover{border-color:#4FC3F7;background:rgba(79,195,247,.12);color:#fff}"
                + ".files{margin-top:12px;display:none;max-height:190px;overflow-y:auto;padding-right:2px}"
                + ".file{display:flex;align-items:center;gap:8px;background:rgba(79,195,247,.08);"
                + "border:1px solid rgba(79,195,247,.2);border-radius:10px;padding:8px 10px;margin-top:8px;font-size:13px}"
                + ".file .nm{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#E1F0FB}"
                + ".file .sz{color:#7FA6C8;font-size:12px;white-space:nowrap}"
                + ".file .x{width:24px;height:24px;border-radius:50%;border:0;flex:none;cursor:pointer;"
                + "background:rgba(255,138,128,.16);color:#FF8A80;font-size:12px;line-height:1}"
                + ".file .x:hover{background:rgba(255,138,128,.38)}"
                + ".btn{display:inline-block;margin-top:16px;background:#4FC3F7;color:#081120;border:0;"
                + "padding:12px 26px;border-radius:999px;font-size:15px;font-weight:700;cursor:pointer;transition:.2s}"
                + ".btn:hover{background:#81D4FA}.btn:disabled{opacity:.5;cursor:default}"
                + ".progress{display:none;margin-top:16px}"
                + ".track{height:8px;border-radius:999px;background:rgba(255,255,255,.1);overflow:hidden}"
                + ".bar{height:100%;width:0;background:linear-gradient(90deg,#4FC3F7,#81D4FA);border-radius:999px;transition:width .15s}"
                + ".ptxt{margin-top:8px;font-size:13px;color:#81D4FA;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                + ".status{margin-top:14px;font-size:14px;min-height:20px;color:#81D4FA}"
                + ".status.err{color:#FF8A80}"
                + ".hint{color:#607D8B;font-size:12px;margin-top:10px}"
                + "#toasts{position:fixed;top:16px;right:16px;display:flex;flex-direction:column;gap:8px;"
                + "z-index:99;max-width:320px}"
                + ".toast{padding:10px 14px;border-radius:10px;font-size:13px;color:#fff;background:#12233D;"
                + "border:1px solid rgba(255,255,255,.14);box-shadow:0 6px 20px rgba(0,0,0,.35);animation:slideIn .22s ease}"
                + ".toast.ok{border-color:rgba(105,240,174,.5);color:#A5F3C7}"
                + ".toast.err{border-color:rgba(255,138,128,.5);color:#FF8A80}"
                + ".toast.hide{opacity:0;transform:translateX(12px);transition:.3s}"
                + "@keyframes slideIn{from{opacity:0;transform:translateY(-6px)}to{opacity:1;transform:none}}";

        return "<!DOCTYPE html><html lang=\"" + lang + "\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title>"
                + "<link rel=\"icon\" type=\"image/png\" href=\"/icon.png\">"
                + "<style>" + css + "</style></head><body>"
                + "<div class=\"top\">BukaMusic</div>"
                + "<div class=\"wrap\"><div class=\"card\">"
                + "<h2>" + h2 + "</h2><p>" + desc + "</p>"
                + "<div class=\"drop\" id=\"drop\">" + dropText + "</div>"
                + "<input type=\"file\" id=\"file\" multiple hidden>"
                + "<div class=\"files\" id=\"files\"></div>"
                + "<button class=\"btn\" id=\"btn\">" + upload + "</button>"
                + "<div class=\"progress\" id=\"progress\"><div class=\"track\"><div class=\"bar\" id=\"bar\"></div></div>"
                + "<div class=\"ptxt\" id=\"ptxt\"></div></div>"
                + "<div class=\"status\" id=\"status\"></div>"
                + "<div class=\"hint\" id=\"hint\"></div>"
                + "</div></div>"
                + "<div id=\"toasts\"></div>"
                + "<script>" + js + "</script></body></html>";
    }
}

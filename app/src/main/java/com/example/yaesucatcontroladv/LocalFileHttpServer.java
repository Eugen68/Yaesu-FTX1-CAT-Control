package com.example.yaesucatcontroladv;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Server HTTP minimal, local (LAN), care expune spre descărcare fișierele de
 * backup (JSON) și log ADIF exportate de aplicație în Downloads/YaesuCatControlADV.
 * Nu expune nimic altceva din stocarea telefonului - lista de fișiere e
 * întotdeauna re-interogată din MediaStore (API 29+) sau din folderul legacy
 * (API < 29), niciodată nu se construiește o cale de fișier direct din
 * parametri primiți de la client (evită path traversal).
 */
class LocalFileHttpServer extends NanoHTTPD {

    private static final String EXPORT_SUBDIR = "YaesuCatControlADV";

    private final Context context;
    private final String accessToken;

    LocalFileHttpServer(Context context, int port, String accessToken) {
        super(port);
        this.context = context.getApplicationContext();
        this.accessToken = accessToken;
    }

    private static class ExportedFile {
        String displayName;
        long size;
        long lastModified;
        long mediaStoreId = -1;
        File legacyFile;
    }

    private List<ExportedFile> listExportedFiles() {
        List<ExportedFile> result = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_SUBDIR + "/";
            String[] projection = {
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_MODIFIED
            };
            String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND "
                    + MediaStore.Downloads.OWNER_PACKAGE_NAME + "=?";
            String[] selectionArgs = {relativePath, context.getPackageName()};
            try (Cursor c = context.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs,
                    MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
                if (c != null) {
                    int idxId = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                    int idxName = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                    int idxSize = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
                    int idxDate = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED);
                    while (c.moveToNext()) {
                        ExportedFile ef = new ExportedFile();
                        ef.mediaStoreId = c.getLong(idxId);
                        ef.displayName = c.getString(idxName);
                        ef.size = c.getLong(idxSize);
                        ef.lastModified = c.getLong(idxDate) * 1000L;
                        result.add(ef);
                    }
                }
            } catch (Exception ignored) {
                // Dacă interogarea eșuează (ex. coloane lipsă pe un OEM ciudat), afișăm listă goală.
            }
        } else {
            File dirDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dirAplicatie = new File(dirDownloads, EXPORT_SUBDIR);
            File[] files = dirAplicatie.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile()) continue;
                    ExportedFile ef = new ExportedFile();
                    ef.displayName = f.getName();
                    ef.size = f.length();
                    ef.lastModified = f.lastModified();
                    ef.legacyFile = f;
                    result.add(ef);
                }
            }
        }
        return result;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();

        // Logo-ul e servit fără token, ca să apară și pe pagina de introdus tokenul.
        if ("/logo.png".equals(uri)) {
            return serveLogo();
        }

        String suppliedToken = session.getParms().get("token");
        if (accessToken != null && !accessToken.isEmpty() && !accessToken.equals(suppliedToken)) {
            // Fără token corect nu arătăm nici măcar o eroare seacă - dăm un
            // mic formular unde userul poate introduce/lipi tokenul de pe
            // ecranul telefonului, ca să nu rămână blocat dacă a deschis
            // link-ul fără coada "?token=..." (des întâlnit când adresa e
            // retastată manual pe alt dispozitiv).
            return serveTokenPrompt(suppliedToken != null);
        }

        if (uri == null || uri.equals("/")) {
            return serveIndex();
        } else if (uri.equals("/download")) {
            return serveDownload(session.getParms().get("name"));
        } else if (uri.equals("/delete")) {
            return serveDelete(session);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
    }

    private Response serveLogo() {
        try {
            InputStream in = context.getResources().openRawResource(R.drawable.fileserver_logo);
            return newFixedLengthResponse(Response.Status.OK, "image/png", in, in.available());
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Logo not found");
        }
    }

    private Response serveTokenPrompt(boolean wrongTokenSupplied) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>YaesuCatControlADV - Files</title>")
                .append("<style>body{font-family:sans-serif;background:#112855;color:#eee;padding:16px;text-align:center}")
                .append("img.logo{display:block;margin:0 auto 14px;width:200px;height:auto}")
                .append("input{font-size:16px;padding:10px;width:120px;box-sizing:border-box;margin:12px auto;display:block;text-align:center}")
                .append("button{font-size:16px;padding:10px 20px;background:#37474F;color:#fff;border:none;border-radius:4px}")
                .append(".err{color:#EF9A9A}</style>")
                .append("</head><body><img class=\"logo\" src=\"/logo.png\" alt=\"logo\"><h2>Access Token Required</h2>");
        if (wrongTokenSupplied) {
            html.append("<p class=\"err\">Invalid token.</p>");
        }
        html.append("<p>Enter the access token shown on the phone, in Settings &rarr; Local File Server.</p>")
                .append("<form method=\"GET\" action=\"/\">")
                .append("<input type=\"text\" name=\"token\" placeholder=\"Access token\" autofocus autocapitalize=\"off\" autocorrect=\"off\">")
                .append("<button type=\"submit\">OPEN</button>")
                .append("</form></body></html>");
        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/html; charset=utf-8", html.toString());
    }

    private Response serveIndex() {
        List<ExportedFile> files = listExportedFiles();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        String tokenSafe = accessToken == null ? "" : accessToken;
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>YaesuCatControlADV - Files</title>")
                .append("<style>body{font-family:sans-serif;background:#112855;color:#eee;padding:16px;padding-top:104px;text-align:left}")
                .append("img.logo{position:absolute;top:16px;left:16px;width:225px;height:auto}")
                .append("a{color:#4FC3F7;text-decoration:none} li{margin:12px 0} .meta{color:#A9B8DC;font-size:12px}")
                .append(".rand-titlu{display:flex;align-items:center;flex-wrap:wrap}")
                .append(".rand-titlu h2{margin:0}")
                .append(".buton-actiune{margin-left:2cm;font-size:14px;padding:8px 18px;border:none;border-radius:4px;color:#fff;cursor:pointer}")
                .append("#btnDelete{background:#546E7A}")
                .append("#btnDelete.activ{background:#B71C1C}")
                .append("#btnCancel{margin-left:10px;background:#546E7A;display:none}")
                .append(".bifa-stergere{display:none;margin-right:10px;transform:scale(1.3);vertical-align:middle}")
                .append("</style>")
                .append("</head><body><img class=\"logo\" src=\"/logo.png\" alt=\"logo\">")
                .append("<div class=\"rand-titlu\"><h2>Backup &amp; Log Files</h2>")
                .append("<button type=\"button\" id=\"btnDelete\" class=\"buton-actiune\" onclick=\"onDeleteClick()\">DELETE</button>")
                .append("<button type=\"button\" id=\"btnCancel\" class=\"buton-actiune\" onclick=\"onCancelClick()\">CANCEL</button>")
                .append("</div>");
        if (files.isEmpty()) {
            html.append("<p>No exported files found. Use EXPORT BACKUP / EXPORT ADIF LOG in the app first.</p>");
        } else {
            html.append("<form id=\"formStergere\" method=\"POST\" action=\"/delete?token=")
                    .append(urlEncode(tokenSafe)).append("\">");
            html.append("<ul>");
            for (ExportedFile ef : files) {
                String link = "/download?name=" + urlEncode(ef.displayName)
                        + "&token=" + urlEncode(tokenSafe);
                html.append("<li><input type=\"checkbox\" class=\"bifa-stergere\" name=\"name\" value=\"")
                        .append(escapeHtml(ef.displayName)).append("\">")
                        .append("<a href=\"").append(link).append("\">")
                        .append(escapeHtml(ef.displayName)).append("</a>")
                        .append("<div class=\"meta\">").append(formatSize(ef.size))
                        .append(" &middot; ").append(sdf.format(new Date(ef.lastModified)))
                        .append("</div></li>");
            }
            html.append("</ul>");
            html.append("</form>");
        }
        html.append("<script>")
                .append("var modSelectie=false;")
                .append("function onDeleteClick(){")
                .append("if(!modSelectie){")
                .append("modSelectie=true;")
                .append("document.querySelectorAll('.bifa-stergere').forEach(function(c){c.style.display='inline-block';});")
                .append("document.getElementById('btnCancel').style.display='inline-block';")
                .append("document.getElementById('btnDelete').classList.add('activ');")
                .append("return;}")
                .append("var bifate=Array.prototype.filter.call(document.querySelectorAll('.bifa-stergere'),function(c){return c.checked;});")
                .append("if(bifate.length===0){alert('Selecteaza cel putin un fisier.');return;}")
                .append("if(confirm('Chiar vrei sa le stergi?')){document.getElementById('formStergere').submit();}")
                .append("}")
                .append("function onCancelClick(){")
                .append("modSelectie=false;")
                .append("document.querySelectorAll('.bifa-stergere').forEach(function(c){c.style.display='none';c.checked=false;});")
                .append("document.getElementById('btnCancel').style.display='none';")
                .append("document.getElementById('btnDelete').classList.remove('activ');")
                .append("}")
                .append("</script>");
        html.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.toString());
    }

    private Response serveDelete(IHTTPSession session) {
        if (!Method.POST.equals(session.getMethod())) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST required");
        }
        try {
            Map<String, String> corpIgnorat = new HashMap<>();
            session.parseBody(corpIgnorat);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad request");
        }
        List<String> numeDeSters = session.getParameters().get("name");
        if (numeDeSters != null && !numeDeSters.isEmpty()) {
            // Comparam cu numele gasite in listarea proaspata - la fel ca la /download,
            // nu construim niciodata o cale de fisier direct dintr-un parametru primit.
            List<ExportedFile> curente = listExportedFiles();
            for (String nume : numeDeSters) {
                for (ExportedFile ef : curente) {
                    if (ef.displayName.equals(nume)) {
                        stergeFisierExportat(ef);
                        break;
                    }
                }
            }
        }
        Response resp = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "");
        resp.addHeader("Location", "/?token=" + urlEncode(accessToken == null ? "" : accessToken));
        return resp;
    }

    private void stergeFisierExportat(ExportedFile ef) {
        try {
            if (ef.legacyFile != null) {
                ef.legacyFile.delete();
            } else if (ef.mediaStoreId >= 0) {
                Uri contentUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ef.mediaStoreId);
                context.getContentResolver().delete(contentUri, null, null);
            }
        } catch (Exception ignored) {
            // Best-effort - daca stergerea unui fisier esueaza (permisiuni etc.), continuam cu restul.
        }
    }

    private Response serveDownload(String name) {
        if (name == null || name.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing file name.");
        }
        // Comparăm cu numele găsite în listarea proaspătă (MediaStore/folder) -
        // nu construim niciodată o cale de fișier direct din parametrul primit.
        for (ExportedFile ef : listExportedFiles()) {
            if (ef.displayName.equals(name)) {
                try {
                    InputStream in;
                    if (ef.legacyFile != null) {
                        in = new FileInputStream(ef.legacyFile);
                    } else {
                        Uri contentUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ef.mediaStoreId);
                        in = context.getContentResolver().openInputStream(contentUri);
                    }
                    if (in == null) throw new IOException("Cannot open file");
                    // "application/octet-stream" pentru fișierele non-JSON (ex. .adi) -
                    // "text/plain" ar face unele browsere să adauge ".txt" la salvare.
                    String mime = name.endsWith(".json") ? "application/json" : "application/octet-stream";
                    Response resp = newFixedLengthResponse(Response.Status.OK, mime, in, ef.size);
                    resp.addHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
                    return resp;
                } catch (Exception e) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                            "Error reading file: " + e.getMessage());
                }
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found.");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}

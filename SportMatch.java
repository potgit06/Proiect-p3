import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * SportMatch - server pe calculator.
 * Baza de date (sportmatch.db) si configurarea (sportmatch.cfg) sunt langa program.
 * Dispozitivele se conecteaza prin browser la adresa afisata in fereastra.
 */
public class SportMatch {

    private static final Path CFG_FILE = Paths.get("sportmatch.cfg");
    private static final Path DB_FILE = Paths.get("sportmatch.db");
    private static final String[] LEVELS = {"Incepator", "Intermediar", "Avansat"};
    private static final int[] LEVEL_MIN_POINTS = {0, 50, 150};

    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS players (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            name         TEXT NOT NULL UNIQUE COLLATE NOCASE,
            availability TEXT NOT NULL,
            zone         TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS player_sports (
            player_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
            sport     TEXT NOT NULL,
            points    INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (player_id, sport))
        """,
        """
        CREATE TABLE IF NOT EXISTS likes (
            from_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
            to_id   INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
            PRIMARY KEY (from_id, to_id))
        """,
        """
        CREATE TABLE IF NOT EXISTS match_log (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            player_id    INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
            sport        TEXT NOT NULL,
            won          INTEGER NOT NULL,
            points_delta INTEGER NOT NULL,
            teammates    TEXT,
            opponents    TEXT,
            played_at    TEXT NOT NULL DEFAULT (datetime('now', 'localtime')))
        """
    };

    /** Pagina web servita de server (fara date, datele vin prin API). */
    private static final String PAGE = """
<!DOCTYPE html>
<html lang="ro">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#1e6f5c">
<title>SportMatch</title>
<style>
:root {
  --main: #1e6f5c;
  --main-dark: #15503f;
  --accent: #f4a259;
  --bg: #f1f5f3;
  --card: #ffffff;
  --text: #1f2d2a;
  --muted: #6b7b77;
  --line: #e1e8e5;
}
* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  background: var(--bg);
  color: var(--text);
  padding-bottom: 76px;
}
header {
  background: linear-gradient(135deg, var(--main), var(--main-dark));
  color: #fff;
  padding: 22px 18px 26px;
  border-radius: 0 0 22px 22px;
  box-shadow: 0 4px 14px rgba(21, 80, 63, 0.25);
}
header h1 { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: 0.3px; }
header p { margin: 4px 0 0; font-size: 14px; opacity: 0.85; }
main { padding: 16px 14px; max-width: 640px; margin: 0 auto; }
.section { display: none; }
.section.active { display: block; }
.card {
  background: var(--card);
  border-radius: 16px;
  padding: 16px;
  margin-bottom: 14px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
}
.card h2 { margin: 0 0 6px; font-size: 17px; font-weight: 650; }
.hint { margin: 0 0 8px; font-size: 13px; color: var(--muted); }
label { display: block; font-size: 13px; color: var(--muted); margin: 12px 0 5px; }
input, select {
  width: 100%;
  padding: 12px 13px;
  border: 1px solid var(--line);
  border-radius: 12px;
  font-size: 16px;
  background: #fff;
  color: var(--text);
  appearance: none;
  -webkit-appearance: none;
}
select {
  background-image: linear-gradient(45deg, transparent 50%, var(--muted) 50%),
    linear-gradient(135deg, var(--muted) 50%, transparent 50%);
  background-position: calc(100% - 18px) 55%, calc(100% - 13px) 55%;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
  padding-right: 34px;
}
input:focus, select:focus {
  outline: none;
  border-color: var(--main);
  box-shadow: 0 0 0 3px rgba(30, 111, 92, 0.15);
}
.btn {
  width: 100%;
  margin-top: 14px;
  padding: 13px;
  border: none;
  border-radius: 12px;
  background: var(--main);
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}
.btn:active { background: var(--main-dark); transform: scale(0.99); }
.btn.accent { background: var(--accent); color: #3b2a12; }
.toggle { display: flex; gap: 8px; margin-top: 6px; }
.toggle button {
  flex: 1;
  padding: 11px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  color: var(--text);
  font-size: 15px;
  cursor: pointer;
}
.toggle button.on { background: var(--main); color: #fff; border-color: var(--main); }
.item {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 14px;
  margin-top: 10px;
  background: #fbfdfc;
}
.item .name { font-weight: 650; font-size: 16px; }
.item .meta { font-size: 13px; color: var(--muted); margin-top: 5px; line-height: 1.45; }
.chip {
  display: inline-block;
  font-size: 12px;
  background: #e2f1eb;
  color: var(--main-dark);
  border-radius: 999px;
  padding: 3px 10px;
  margin: 6px 5px 0 0;
}
.empty { color: var(--muted); font-size: 14px; text-align: center; padding: 14px 0 4px; }
.result-box {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  background: #e8f4ef;
  color: var(--main-dark);
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-line;
}
.result-box:empty { display: none; }
.toast {
  position: fixed;
  left: 50%;
  bottom: 88px;
  transform: translateX(-50%);
  background: #22302c;
  color: #fff;
  padding: 11px 18px;
  border-radius: 12px;
  font-size: 14px;
  display: none;
  max-width: 90%;
  text-align: center;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.25);
  z-index: 10;
}
nav {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: #fff;
  border-top: 1px solid var(--line);
  display: flex;
  padding-bottom: env(safe-area-inset-bottom, 0px);
  z-index: 5;
}
nav button {
  flex: 1;
  border: none;
  background: none;
  padding: 12px 2px 13px;
  font-size: 13px;
  color: var(--muted);
  cursor: pointer;
  border-top: 3px solid transparent;
}
nav button.active { color: var(--main); font-weight: 650; border-top-color: var(--main); }
</style>
</head>
<body>

<header>
  <h1>SportMatch</h1>
  <p>Gaseste parteneri de joc in orasul tau</p>
</header>

<main>

  <section class="section active" id="sec-profiles">
    <div class="card">
      <h2>Profil nou</h2>
      <p class="hint">Sporturile incep toate de la nivelul Incepator.</p>
      <label>Nume</label>
      <input id="name" placeholder="Ex: Ana">
      <label>Sporturi</label>
      <input id="sports" placeholder="Ex: tenis, baschet">
      <label>Disponibilitate</label>
      <input id="avail" placeholder="Ex: weekend seara">
      <label>Zona / cartier</label>
      <input id="zone" placeholder="Ex: Fabric">
      <button class="btn" onclick="addPlayer()">Creeaza profil</button>
    </div>
    <div class="card">
      <h2>Jucatori</h2>
      <div id="players"></div>
    </div>
  </section>

  <section class="section" id="sec-search">
    <div class="card">
      <h2>Cauta parteneri</h2>
      <p class="hint">Gasesti jucatori cu acelasi sport, ordonati dupa compatibilitate.</p>
      <label>Profilul tau</label>
      <select id="searchMe"></select>
      <label>Sport</label>
      <input id="searchSport" placeholder="Ex: tenis">
      <button class="btn" onclick="search()">Cauta</button>
    </div>
    <div class="card">
      <div id="searchResults"></div>
    </div>
  </section>

  <section class="section" id="sec-matches">
    <div class="card">
      <h2>Apreciaza un jucator</h2>
      <p class="hint">Potrivire apare cand va apreciati reciproc.</p>
      <label>Tu</label>
      <select id="likeFrom"></select>
      <label>Apreciezi</label>
      <select id="likeTo"></select>
      <button class="btn" onclick="like()">Apreciaza</button>
    </div>
    <div class="card">
      <h2>Potrivirile tale</h2>
      <label>Profilul tau</label>
      <select id="matchMe"></select>
      <button class="btn accent" onclick="showMatches()">Arata potrivirile</button>
      <div id="matches"></div>
    </div>
  </section>

  <section class="section" id="sec-record">
    <div class="card">
      <h2>Inregistreaza un meci</h2>
      <p class="hint">Punctele se calculeaza automat in functie de niveluri.</p>
      <label>Profilul tau</label>
      <select id="recordMe"></select>
      <label>Sport</label>
      <input id="recordSport" placeholder="Ex: tenis">
      <label>Rezultat</label>
      <input type="hidden" id="result" value="1">
      <div class="toggle">
        <button id="win" class="on" onclick="setResult('1')">Victorie</button>
        <button id="loss" onclick="setResult('0')">Infrangere</button>
      </div>
      <label>Coechipieri</label>
      <input id="teammates" placeholder="Separati prin virgula, sau gol">
      <label>Adversari</label>
      <input id="opponents" placeholder="Separati prin virgula">
      <button class="btn" onclick="recordMatch()">Inregistreaza meciul</button>
      <div id="recordResult" class="result-box"></div>
    </div>
  </section>

</main>

<div id="msg" class="toast"></div>

<nav>
  <button class="active" onclick="go('profiles', this)">Profil</button>
  <button onclick="go('search', this)">Cauta</button>
  <button onclick="go('matches', this)">Potriviri</button>
  <button onclick="go('record', this)">Meci</button>
</nav>

<script>
let pwd = localStorage.getItem('sm_pwd') || '';
let toastTimer = null;

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
function v(id) {
  return document.getElementById(id).value.trim();
}
function showMsg(text) {
  const m = document.getElementById('msg');
  m.textContent = text;
  m.style.display = text ? 'block' : 'none';
  clearTimeout(toastTimer);
  if (text) toastTimer = setTimeout(() => { m.style.display = 'none'; }, 3500);
}
function fail(e) {
  showMsg(e.message);
}

function go(name, btn) {
  document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
  document.getElementById('sec-' + name).classList.add('active');
  document.querySelectorAll('nav button').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  window.scrollTo(0, 0);
}

function setResult(val) {
  document.getElementById('result').value = val;
  document.getElementById('win').classList.toggle('on', val === '1');
  document.getElementById('loss').classList.toggle('on', val === '0');
}

async function api(path, method, data) {
  const opts = { method: method || 'GET', headers: { 'X-Parola': pwd } };
  if (data) {
    opts.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    opts.body = new URLSearchParams(data).toString();
  }
  const r = await fetch('/api/' + path, opts);
  if (r.status === 401) {
    const p = prompt('Parola serverului:');
    if (!p) throw new Error('Parola este necesara.');
    pwd = p;
    localStorage.setItem('sm_pwd', pwd);
    return api(path, method, data);
  }
  const j = await r.json();
  if (!j.ok) throw new Error(j.message);
  return j;
}

async function loadPlayers() {
  try {
    const j = await api('players');
    const list = document.getElementById('players');
    list.innerHTML = j.items.length ? '' : '<div class="empty">Nu exista profiluri inca.</div>';
    j.items.forEach(p => {
      const chips = p.sports.map(s => '<span class="chip">' + esc(s.sport) + ' · ' + esc(s.level) + ' (' + s.points + ')</span>').join('');
      list.innerHTML += '<div class="item"><div class="name">' + esc(p.name) + '</div>' +
        '<div class="meta">' + chips + '<br>' + esc(p.zone) + ' · ' + esc(p.availability) + '</div></div>';
    });
    const opts = j.items.map(p => '<option value="' + p.id + '">' + esc(p.name) + '</option>').join('');
    ['searchMe', 'likeFrom', 'likeTo', 'matchMe', 'recordMe'].forEach(id => {
      const sel = document.getElementById(id);
      const old = sel.value;
      sel.innerHTML = opts;
      if (old) sel.value = old;
    });
  } catch (e) { fail(e); }
}

async function addPlayer() {
  try {
    const j = await api('players', 'POST', {
      name: v('name'), sports: v('sports'), availability: v('avail'), zone: v('zone')
    });
    ['name', 'sports', 'avail', 'zone'].forEach(id => document.getElementById(id).value = '');
    showMsg(j.message);
    loadPlayers();
  } catch (e) { fail(e); }
}

async function search() {
  try {
    const j = await api('search?me=' + encodeURIComponent(v('searchMe')) +
      '&sport=' + encodeURIComponent(v('searchSport')));
    const box = document.getElementById('searchResults');
    box.innerHTML = j.items.length ? '' : '<div class="empty">Nu am gasit jucatori pentru acest sport.</div>';
    j.items.forEach(p => {
      box.innerHTML += '<div class="item"><div class="name">' + esc(p.name) + '</div>' +
        '<div class="meta"><span class="chip">' + esc(p.level) + ' (' + p.points + ' puncte)</span><br>' +
        esc(p.zone) + ' · ' + esc(p.availability) + '</div></div>';
    });
  } catch (e) { fail(e); }
}

async function like() {
  try {
    const j = await api('like', 'POST', { from: v('likeFrom'), to: v('likeTo') });
    showMsg(j.message);
  } catch (e) { fail(e); }
}

async function showMatches() {
  try {
    const j = await api('matches?me=' + encodeURIComponent(v('matchMe')));
    const box = document.getElementById('matches');
    box.innerHTML = j.items.length ? '' : '<div class="empty">Nu ai potriviri inca.</div>';
    j.items.forEach(m => {
      box.innerHTML += '<div class="item"><div class="name">' + esc(m.name) + '</div>' +
        '<div class="meta"><span class="chip">' + esc(m.common) + '</span><br>' +
        esc(m.zone) + ' · ' + esc(m.availability) + '</div></div>';
    });
  } catch (e) { fail(e); }
}

async function recordMatch() {
  try {
    const j = await api('match', 'POST', {
      me: v('recordMe'), sport: v('recordSport'), won: v('result'),
      teammates: v('teammates'), opponents: v('opponents')
    });
    document.getElementById('recordResult').textContent = j.message;
    ['recordSport', 'teammates', 'opponents'].forEach(id => document.getElementById(id).value = '');
    loadPlayers();
  } catch (e) { fail(e); }
}

loadPlayers();
</script>
</body>
</html>
        """;

    private static int port;
    private static String password;
    private static Connection db;
    private static HttpServer server;

    record Player(int id, String name, String availability, String zone) {}

    record Scored(Player player, int points, int score) {}

    // =====================================================================
    //  Pornire
    // =====================================================================

    public static void main(String[] args) {
        try {
            loadConfig();
            openDb();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Eroare la pornire:\n" + e.getMessage(),
                    "SportMatch", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            startServer();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                    "Nu pot porni serverul pe portul " + port + ".\nProbabil il foloseste deja alt program.\n"
                            + "Schimba portul in sportmatch.cfg.",
                    "SportMatch", JOptionPane.ERROR_MESSAGE);
            closeDb();
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null) server.stop(0);
            closeDb();
        }));
        SwingUtilities.invokeLater(SportMatch::createWindow);
    }

    private static void loadConfig() throws IOException {
        Properties props = new Properties();
        if (Files.exists(CFG_FILE)) {
            try (Reader r = Files.newBufferedReader(CFG_FILE, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        }
        port = Integer.parseInt(props.getProperty("port", "8080").trim());
        password = props.getProperty("parola", "").trim();
        if (password.isEmpty()) {
            password = randomPassword(8);
            props.setProperty("port", String.valueOf(port));
            props.setProperty("parola", password);
            try (Writer w = Files.newBufferedWriter(CFG_FILE, StandardCharsets.UTF_8)) {
                props.store(w, "SportMatch - setari server. Poti schimba parola si portul.");
            }
        }
    }

    private static String randomPassword(int length) {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    private static void openDb() throws ClassNotFoundException, SQLException {
        Class.forName("org.sqlite.JDBC");
        db = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE.toAbsolutePath());
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            for (String sql : SCHEMA) st.execute(sql);
        }
    }

    private static void closeDb() {
        try {
            if (db != null) db.close();
        } catch (SQLException ignored) {
        }
    }

    private static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", SportMatch::handle);
        // un singur fir: accesul la baza de date ramane secvential
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    // =====================================================================
    //  HTTP
    // =====================================================================

    private static void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if (method.equals("GET") && path.equals("/")) {
                send(ex, 200, "text/html; charset=utf-8", PAGE);
                return;
            }
            if (!path.startsWith("/api/")) {
                send(ex, 404, "text/plain; charset=utf-8", "Nu exista.");
                return;
            }
            if (!password.equals(ex.getRequestHeaders().getFirst("X-Parola"))) {
                send(ex, 401, "application/json; charset=utf-8", error("Parola gresita sau lipsa."));
                return;
            }
            Map<String, String> params = parse(ex.getRequestURI().getRawQuery());
            if (method.equals("POST")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                params.putAll(parse(body));
            }
            send(ex, 200, "application/json; charset=utf-8", api(method, path, params));
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json; charset=utf-8", error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, "application/json; charset=utf-8", error("Eroare server: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String api(String method, String path, Map<String, String> p) throws SQLException {
        if (method.equals("GET") && path.equals("/api/check")) return ok("Conectat.");
        if (method.equals("GET") && path.equals("/api/players")) return playersJson();
        if (method.equals("POST") && path.equals("/api/players")) return addPlayer(p);
        if (method.equals("GET") && path.equals("/api/search")) return search(p);
        if (method.equals("POST") && path.equals("/api/like")) return like(p);
        if (method.equals("GET") && path.equals("/api/matches")) return matches(p);
        if (method.equals("POST") && path.equals("/api/match")) return recordMatch(p);
        throw new IllegalArgumentException("Ruta necunoscuta.");
    }

    // ---------- JSON (scris manual, fara biblioteci) ----------

    private static String q(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String ok(String message) {
        return "{\"ok\":true,\"message\":" + q(message) + "}";
    }

    private static String okItems(List<String> items) {
        return "{\"ok\":true,\"items\":[" + String.join(",", items) + "]}";
    }

    private static String error(String message) {
        return "{\"ok\":false,\"message\":" + q(message) + "}";
    }

    // ---------- endpoint-uri ----------

    private static String req(Map<String, String> p, String key) {
        String v = p.getOrDefault(key, "").trim();
        if (v.isEmpty()) throw new IllegalArgumentException("Lipseste campul: " + key);
        return v;
    }

    private static int intParam(Map<String, String> p, String key) {
        try {
            return Integer.parseInt(req(p, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valoare invalida pentru: " + key);
        }
    }

    private static Player playerById(int id) throws SQLException {
        List<Player> found = queryPlayers("SELECT id, name, availability, zone FROM players WHERE id = ?", id);
        if (found.isEmpty()) throw new IllegalArgumentException("Profilul nu exista.");
        return found.get(0);
    }

    private static String playersJson() throws SQLException {
        List<String> items = new ArrayList<>();
        for (Player p : allPlayers()) items.add(playerJson(p));
        return okItems(items);
    }

    private static String playerJson(Player p) throws SQLException {
        StringBuilder sports = new StringBuilder("[");
        for (Map.Entry<String, Integer> e : sportPoints(p.id()).entrySet()) {
            if (sports.length() > 1) sports.append(",");
            sports.append("{\"sport\":").append(q(e.getKey()))
                    .append(",\"level\":").append(q(LEVELS[levelFromPoints(e.getValue())]))
                    .append(",\"points\":").append(e.getValue()).append("}");
        }
        sports.append("]");
        return "{\"id\":" + p.id() + ",\"name\":" + q(p.name()) + ",\"zone\":" + q(p.zone())
                + ",\"availability\":" + q(p.availability()) + ",\"sports\":" + sports + "}";
    }

    private static String addPlayer(Map<String, String> p) throws SQLException {
        String name = req(p, "name");
        if (findPlayerByName(name) != null) {
            throw new IllegalArgumentException("Exista deja un profil cu acest nume.");
        }
        Set<String> sports = parseSports(p.getOrDefault("sports", ""));
        if (sports.isEmpty()) throw new IllegalArgumentException("Introdu cel putin un sport.");
        String availability = p.getOrDefault("availability", "").trim();
        String zone = p.getOrDefault("zone", "").trim();

        db.setAutoCommit(false);
        try {
            exec("INSERT INTO players (name, availability, zone) VALUES (?, ?, ?)", name, availability, zone);
            int id = findPlayerByName(name).id();
            for (String sport : sports) {
                exec("INSERT INTO player_sports (player_id, sport, points) VALUES (?, ?, 0)", id, sport);
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
        return ok("Profil creat pentru " + name + ". Toate sporturile incep de la " + LEVELS[0] + ".");
    }

    private static String search(Map<String, String> p) throws SQLException {
        Player me = playerById(intParam(p, "me"));
        String sport = req(p, "sport").toLowerCase();
        if (!hasSport(me.id(), sport)) {
            throw new IllegalArgumentException("Sportul " + sport + " nu este in profilul tau.");
        }
        int myLevel = levelFromPoints(pointsOf(me.id(), sport));

        List<Scored> results = new ArrayList<>();
        for (Player c : playersWithSport(sport, me.id())) {
            int pts = pointsOf(c.id(), sport);
            results.add(new Scored(c, pts, matchScore(me, myLevel, c, levelFromPoints(pts))));
        }
        results.sort(Comparator.comparingInt(Scored::score).reversed());

        List<String> items = new ArrayList<>();
        for (Scored r : results) {
            Player c = r.player();
            items.add("{\"id\":" + c.id() + ",\"name\":" + q(c.name())
                    + ",\"level\":" + q(LEVELS[levelFromPoints(r.points())])
                    + ",\"points\":" + r.points()
                    + ",\"zone\":" + q(c.zone())
                    + ",\"availability\":" + q(c.availability()) + "}");
        }
        return okItems(items);
    }

    private static String like(Map<String, String> p) throws SQLException {
        Player from = playerById(intParam(p, "from"));
        Player to = playerById(intParam(p, "to"));
        if (from.id() == to.id()) throw new IllegalArgumentException("Nu te poti aprecia pe tine.");
        exec("INSERT OR IGNORE INTO likes (from_id, to_id) VALUES (?, ?)", from.id(), to.id());
        if (isMutual(from.id(), to.id())) {
            return ok("Potrivire! " + from.name() + " si " + to.name() + " va puteti contacta pentru un joc.");
        }
        return ok("Aprecierea a fost salvata. Te vom anunta cand si celalalt apreciaza.");
    }

    private static String matches(Map<String, String> p) throws SQLException {
        Player me = playerById(intParam(p, "me"));
        Set<String> mySports = sportPoints(me.id()).keySet();
        List<String> items = new ArrayList<>();
        for (Player o : matchesFor(me.id())) {
            Set<String> common = new TreeSet<>(mySports);
            common.retainAll(sportPoints(o.id()).keySet());
            items.add("{\"id\":" + o.id() + ",\"name\":" + q(o.name())
                    + ",\"common\":" + q(common.isEmpty() ? "-" : join(common))
                    + ",\"zone\":" + q(o.zone())
                    + ",\"availability\":" + q(o.availability()) + "}");
        }
        return okItems(items);
    }

    private static String recordMatch(Map<String, String> p) throws SQLException {
        Player me = playerById(intParam(p, "me"));
        String sport = req(p, "sport").toLowerCase();
        if (!hasSport(me.id(), sport)) {
            throw new IllegalArgumentException("Sportul " + sport + " nu este in profilul tau.");
        }
        boolean won = "1".equals(p.get("won"));

        List<Player> teammates = resolveNames(p.getOrDefault("teammates", ""), me, sport);
        List<Player> opponents = resolveNames(p.getOrDefault("opponents", ""), me, sport);
        if (opponents.isEmpty()) throw new IllegalArgumentException("Trebuie sa introduci cel putin un adversar.");

        int ownLevel = levelFromPoints(pointsOf(me.id(), sport));
        double teamAvg = teammates.isEmpty() ? ownLevel : averageLevel(teammates, sport);
        double oppAvg = averageLevel(opponents, sport);
        int delta = computePoints(won, ownLevel, teamAvg, oppAvg);

        int oldPts = pointsOf(me.id(), sport);
        int newPts = Math.max(0, oldPts + delta);
        exec("UPDATE player_sports SET points = ? WHERE player_id = ? AND sport = ?", newPts, me.id(), sport);
        exec("INSERT INTO match_log (player_id, sport, won, points_delta, teammates, opponents) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                me.id(), sport, won ? 1 : 0, delta, namesOf(teammates), namesOf(opponents));

        String text = String.format("Meci inregistrat: %s, %+d puncte. Total %s: %d puncte.",
                won ? "victorie" : "infrangere", delta, sport, newPts);
        int oldLevel = levelFromPoints(oldPts);
        int newLevel = levelFromPoints(newPts);
        if (newLevel > oldLevel) text += " Felicitari! Ai urcat la nivelul " + LEVELS[newLevel] + ".";
        if (newLevel < oldLevel) text += " Ai coborat la nivelul " + LEVELS[newLevel] + ".";
        return ok(text);
    }

    /** Returneaza jucatorii din lista; arunca eroare daca lista are ceva invalid. */
    private static List<Player> resolveNames(String raw, Player me, String sport) throws SQLException {
        List<Player> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        for (String n : raw.split(",")) {
            String name = n.trim();
            if (name.isEmpty()) continue;
            Player p = findPlayerByName(name);
            if (p == null) throw new IllegalArgumentException("Nu exista profilul \"" + name + "\".");
            if (p.id() == me.id()) throw new IllegalArgumentException("Nu te poti include singur in lista.");
            if (!hasSport(p.id(), sport)) {
                throw new IllegalArgumentException(p.name() + " nu are sportul " + sport + " in profil.");
            }
            boolean duplicate = false;
            for (Player x : list) if (x.id() == p.id()) duplicate = true;
            if (!duplicate) list.add(p);
        }
        return list;
    }

    // =====================================================================
    //  Logica de baza (identica cu versiunea de calculator)
    // =====================================================================

    private static double averageLevel(List<Player> ps, String sport) throws SQLException {
        if (ps.isEmpty()) return 0;
        double sum = 0;
        for (Player p : ps) sum += levelFromPoints(pointsOf(p.id(), sport));
        return sum / ps.size();
    }

    private static int levelFromPoints(int pts) {
        for (int i = LEVEL_MIN_POINTS.length - 1; i >= 0; i--) {
            if (pts >= LEVEL_MIN_POINTS[i]) return i;
        }
        return 0;
    }

    private static int matchScore(Player a, int aLevel, Player b, int bLevel) {
        int s = 0;
        if (a.zone().equalsIgnoreCase(b.zone())) s += 3;
        s += 2 - Math.min(2, Math.abs(aLevel - bLevel));
        if (a.availability().equalsIgnoreCase(b.availability())) s += 2;
        return s;
    }

    /**
     * Victorie: 10 + 5*oppDiff + 3*teamDiff, minim +3
     * Infrangere: -(8 - 4*oppDiff + 2*teamDiff), maxim -1
     */
    private static int computePoints(boolean won, int ownLevel, double teamAvg, double oppAvg) {
        double oppDiff = oppAvg - ownLevel;
        double teamDiff = ownLevel - teamAvg;
        double pts = won
                ? Math.max(3, 10 + 5 * oppDiff + 3 * teamDiff)
                : Math.min(-1, -(8 - 4 * oppDiff + 2 * teamDiff));
        return (int) Math.round(pts);
    }

    private static Set<String> parseSports(String raw) {
        Set<String> out = new TreeSet<>();
        for (String s : raw.split(",")) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String namesOf(List<Player> ps) {
        List<String> names = new ArrayList<>();
        for (Player p : ps) names.add(p.name());
        return join(names);
    }

    private static String join(Collection<String> items) {
        return String.join(", ", items);
    }

    // =====================================================================
    //  Acces la baza de date
    // =====================================================================

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        }
    }

    private static int scalarInt(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static List<Player> queryPlayers(String sql, Object... args) throws SQLException {
        List<Player> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Player(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    private static List<Player> allPlayers() throws SQLException {
        return queryPlayers("SELECT id, name, availability, zone FROM players ORDER BY name");
    }

    private static Player findPlayerByName(String name) throws SQLException {
        List<Player> found = queryPlayers(
                "SELECT id, name, availability, zone FROM players WHERE name = ?", name.trim());
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Player> playersWithSport(String sport, int excludeId) throws SQLException {
        return queryPlayers("SELECT p.id, p.name, p.availability, p.zone FROM players p "
                + "JOIN player_sports s ON s.player_id = p.id "
                + "WHERE s.sport = ? AND p.id <> ? ORDER BY p.id", sport, excludeId);
    }

    private static List<Player> matchesFor(int id) throws SQLException {
        return queryPlayers("SELECT p.id, p.name, p.availability, p.zone FROM players p "
                + "WHERE p.id <> ? "
                + "AND EXISTS (SELECT 1 FROM likes l WHERE l.from_id = ? AND l.to_id = p.id) "
                + "AND EXISTS (SELECT 1 FROM likes l WHERE l.from_id = p.id AND l.to_id = ?) "
                + "ORDER BY p.name", id, id, id);
    }

    private static Map<String, Integer> sportPoints(int id) throws SQLException {
        Map<String, Integer> m = new TreeMap<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT sport, points FROM player_sports WHERE player_id = ? ORDER BY sport")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) m.put(rs.getString(1), rs.getInt(2));
            }
        }
        return m;
    }

    private static int pointsOf(int id, String sport) throws SQLException {
        return scalarInt("SELECT points FROM player_sports WHERE player_id = ? AND sport = ?", id, sport);
    }

    private static boolean hasSport(int id, String sport) throws SQLException {
        return sportPoints(id).containsKey(sport);
    }

    private static boolean isMutual(int a, int b) throws SQLException {
        return scalarInt("SELECT COUNT(*) FROM likes WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)",
                a, b, b, a) == 2;
    }

    // =====================================================================
    //  Fereastra de stare
    // =====================================================================

    private static void createWindow() {
        JFrame f = new JFrame("SportMatch - server");
        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        JLabel info = new JLabel("<html>"
                + "<b>Serverul SportMatch ruleaza.</b><br><br>"
                + "Pe acest calculator: http://localhost:" + port + "<br>"
                + "Pe aceeasi retea Wi-Fi: http://" + localIp() + ":" + port + "<br><br>"
                + "Parola: <b>" + password + "</b><br><br>"
                + "Acces de pe internet: vezi fisierul INTERNET.txt<br><br>"
                + "Baza de date: " + DB_FILE.toAbsolutePath()
                + "</html>");

        JButton stop = new JButton("Opreste serverul");
        stop.addActionListener(e -> System.exit(0));

        JPanel p = new JPanel(new BorderLayout(12, 12));
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        p.add(info, BorderLayout.CENTER);
        p.add(stop, BorderLayout.SOUTH);

        f.setContentPane(p);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (SocketException ignored) {
        }
        return "IP-ul-calculatorului";
    }
}
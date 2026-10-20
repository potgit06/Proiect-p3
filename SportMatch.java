import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * SportMatch - server pe calculator cu conturi, autentificare cu parola + cod SMS.
 * Fisiere langa program: sportmatch.db (date), sportmatch.cfg (setari, inclusiv Twilio).
 */
public class SportMatch {

    private static final Path CFG_FILE = Paths.get("sportmatch.cfg");
    private static final Path DB_FILE = Paths.get("sportmatch.db");
    private static final String[] LEVELS = {"Incepator", "Intermediar", "Avansat"};
    private static final int[] LEVEL_MIN_POINTS = {0, 50, 150};
    private static final long SESSION_MS = 30L * 24 * 3600 * 1000;
    private static final long CODE_MS = 5L * 60 * 1000;
    private static final int MAX_ATTEMPTS = 5;
    private static final int PBKDF2_ITER = 120_000;
    private static final SecureRandom RND = new SecureRandom();
    private static final String JSON = "application/json; charset=utf-8";

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
        """,
        """
        CREATE TABLE IF NOT EXISTS users (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
            email         TEXT NOT NULL UNIQUE COLLATE NOCASE,
            phone         TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            player_id     INTEGER NOT NULL UNIQUE REFERENCES players(id) ON DELETE CASCADE)
        """
    };

    /** Pagina web (ecrane de login, cod SMS, inregistrare si aplicatia). */
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
  color: var(--text);
  background: var(--bg);
}
.screen { display: none; }
.screen.active { display: block; animation: fadeIn 0.45s ease both; }
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: none; }
}

/* ---------- autentificare ---------- */
.auth-bg {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 28px 18px;
  background: linear-gradient(-45deg, #1e6f5c, #15503f, #2a8c73, #0f3d31);
  background-size: 400% 400%;
  animation: gradientShift 12s ease infinite;
}
@keyframes gradientShift {
  0% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
  100% { background-position: 0% 50%; }
}
.brand { text-align: center; color: #fff; margin-bottom: 18px; }
.logo {
  width: 92px;
  height: 92px;
  animation: floatBall 3.2s ease-in-out infinite;
  filter: drop-shadow(0 8px 14px rgba(0, 0, 0, 0.25));
}
@keyframes floatBall {
  0%, 100% { transform: translateY(0) rotate(0deg); }
  50% { transform: translateY(-10px) rotate(180deg); }
}
.brand h1 { margin: 10px 0 2px; font-size: 28px; letter-spacing: 0.5px; }
.brand p { margin: 0; opacity: 0.85; font-size: 14px; }
.auth-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: 20px;
  padding: 22px 20px;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.25);
  animation: popIn 0.5s ease both;
}
@keyframes popIn {
  from { opacity: 0; transform: scale(0.94); }
  to { opacity: 1; transform: none; }
}
.auth-card h2 { margin: 0 0 4px; font-size: 20px; }
.auth-card .sub { margin: 0 0 4px; color: var(--muted); font-size: 14px; line-height: 1.4; }
.shake { animation: shake 0.45s; }
@keyframes shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-8px); }
  40% { transform: translateX(8px); }
  60% { transform: translateX(-6px); }
  80% { transform: translateX(6px); }
}

/* ---------- elemente comune ---------- */
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
  transition: border-color 0.2s, box-shadow 0.2s;
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
  margin-top: 16px;
  padding: 13px;
  border: none;
  border-radius: 12px;
  background: var(--main);
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.1s, background 0.2s;
}
.btn:hover { background: var(--main-dark); }
.btn:active { transform: scale(0.98); }
.btn.accent { background: var(--accent); color: #3b2a12; }
.link {
  display: block;
  width: 100%;
  margin-top: 12px;
  padding: 8px;
  border: none;
  background: none;
  color: var(--main);
  font-size: 14px;
  cursor: pointer;
}
.link:hover { text-decoration: underline; }
.toast {
  position: fixed;
  left: 50%;
  bottom: 92px;
  transform: translateX(-50%);
  background: #22302c;
  color: #fff;
  padding: 11px 18px;
  border-radius: 12px;
  font-size: 14px;
  display: none;
  max-width: 92%;
  text-align: center;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.25);
  z-index: 10;
  animation: toastIn 0.3s ease both;
}
@keyframes toastIn {
  from { opacity: 0; transform: translate(-50%, 14px); }
  to { opacity: 1; transform: translate(-50%, 0); }
}

/* ---------- aplicatia ---------- */
.app-head {
  background: linear-gradient(135deg, var(--main), var(--main-dark));
  color: #fff;
  padding: 16px 16px 20px;
  border-radius: 0 0 22px 22px;
  box-shadow: 0 4px 14px rgba(21, 80, 63, 0.25);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.head-left { display: flex; align-items: center; gap: 12px; }
.mini-logo { width: 44px; height: 44px; animation: floatBall 3.2s ease-in-out infinite; }
.app-head h1 { margin: 0; font-size: 20px; }
.app-head p { margin: 2px 0 0; font-size: 13px; opacity: 0.85; }
.out {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 10px;
  padding: 7px 12px;
  font-size: 13px;
  cursor: pointer;
}
main { padding: 16px 14px 100px; max-width: 640px; margin: 0 auto; }
.section { display: none; }
.section.active { display: block; animation: fadeIn 0.35s ease both; }
.card {
  background: var(--card);
  border-radius: 16px;
  padding: 16px;
  margin-bottom: 14px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
}
.card h2 { margin: 0 0 6px; font-size: 17px; font-weight: 650; }
.hint { margin: 0 0 8px; font-size: 13px; color: var(--muted); }
.item {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 14px;
  margin-top: 10px;
  background: #fbfdfc;
  animation: popItem 0.35s ease both;
}
@keyframes popItem {
  from { opacity: 0; transform: scale(0.97); }
  to { opacity: 1; transform: none; }
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
  transition: background 0.2s, color 0.2s;
}
.toggle button.on { background: var(--main); color: #fff; border-color: var(--main); }
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
  transition: color 0.2s;
}
nav button.active { color: var(--main); font-weight: 650; border-top-color: var(--main); }
</style>
</head>
<body>

<!-- ================= LOGIN ================= -->
<div id="screen-login" class="screen active">
  <div class="auth-bg">
    <div class="brand">
      <svg class="logo" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#ffffff"/><path d="M50 36 L63.3 45.7 L58.2 61.3 L41.8 61.3 L36.7 45.7 Z" fill="#1e6f5c"/><path d="M50 36 L50 6 M63.3 45.7 L88 27 M58.2 61.3 L74 84 M41.8 61.3 L26 84 M36.7 45.7 L12 27" stroke="#1e6f5c" stroke-width="3" fill="none" stroke-linecap="round"/></svg>
      <h1>SportMatch</h1>
      <p>Gaseste parteneri de joc in orasul tau</p>
    </div>
    <div class="auth-card" id="card-login">
      <h2>Autentificare</h2>
      <p class="sub">Introdu utilizatorul sau emailul si parola. Apoi vei primi un cod SMS.</p>
      <label>Utilizator sau email</label>
      <input id="loginId" autocomplete="username">
      <label>Parola</label>
      <input id="loginPwd" type="password" autocomplete="current-password">
      <button class="btn" onclick="login()">Continua</button>
      <button class="link" onclick="show('register')">Nu ai cont? Creeaza unul</button>
    </div>
  </div>
</div>

<!-- ================= COD SMS ================= -->
<div id="screen-code" class="screen">
  <div class="auth-bg">
    <div class="brand">
      <svg class="logo" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#ffffff"/><path d="M50 36 L63.3 45.7 L58.2 61.3 L41.8 61.3 L36.7 45.7 Z" fill="#1e6f5c"/><path d="M50 36 L50 6 M63.3 45.7 L88 27 M58.2 61.3 L74 84 M41.8 61.3 L26 84 M36.7 45.7 L12 27" stroke="#1e6f5c" stroke-width="3" fill="none" stroke-linecap="round"/></svg>
      <h1>Verificare</h1>
    </div>
    <div class="auth-card" id="card-code">
      <h2>Cod SMS</h2>
      <p class="sub">Am trimis un cod de 6 cifre pe numarul <b id="codePhone"></b>.</p>
      <label>Codul primit</label>
      <input id="code" inputmode="numeric" maxlength="6" placeholder="123456">
      <button class="btn" onclick="verifyCode()">Confirma</button>
      <button class="link" onclick="show('login')">Inapoi</button>
    </div>
  </div>
</div>

<!-- ================= INREGISTRARE ================= -->
<div id="screen-register" class="screen">
  <div class="auth-bg">
    <div class="brand">
      <svg class="logo" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#ffffff"/><path d="M50 36 L63.3 45.7 L58.2 61.3 L41.8 61.3 L36.7 45.7 Z" fill="#1e6f5c"/><path d="M50 36 L50 6 M63.3 45.7 L88 27 M58.2 61.3 L74 84 M41.8 61.3 L26 84 M36.7 45.7 L12 27" stroke="#1e6f5c" stroke-width="3" fill="none" stroke-linecap="round"/></svg>
      <h1>Cont nou</h1>
    </div>
    <div class="auth-card" id="card-register">
      <h2>Creeaza cont</h2>
      <p class="sub">Telefonul trebuie sa fie in format international, de exemplu +40712345678.</p>
      <label>Utilizator</label>
      <input id="regUser" placeholder="Ex: ana.popescu">
      <label>Email</label>
      <input id="regEmail" type="email" placeholder="ana@exemplu.com">
      <label>Telefon (pentru codul SMS)</label>
      <input id="regPhone" inputmode="tel" placeholder="+40712345678">
      <label>Parola (minim 8 caractere)</label>
      <input id="regPwd" type="password" autocomplete="new-password">
      <label>Sporturi</label>
      <input id="regSports" placeholder="Ex: tenis, baschet">
      <label>Disponibilitate</label>
      <input id="regAvail" placeholder="Ex: weekend seara">
      <label>Zona / cartier</label>
      <input id="regZone" placeholder="Ex: Fabric">
      <button class="btn" onclick="register()">Creeaza cont</button>
      <button class="link" onclick="show('login')">Am deja cont</button>
    </div>
  </div>
</div>

<!-- ================= APLICATIA ================= -->
<div id="screen-app" class="screen">
  <header class="app-head">
    <div class="head-left">
      <svg class="mini-logo" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#ffffff"/><path d="M50 36 L63.3 45.7 L58.2 61.3 L41.8 61.3 L36.7 45.7 Z" fill="#1e6f5c"/></svg>
      <div>
        <h1>SportMatch</h1>
        <p id="hello"></p>
      </div>
    </div>
    <button class="out" onclick="logout()">Iesi</button>
  </header>

  <main>
    <section class="section active" id="sec-profiles">
      <div class="card">
        <h2>Sporturile mele</h2>
        <p class="hint">Fiecare sport incepe de la nivelul Incepator.</p>
        <label>Adauga sport</label>
        <input id="newSport" placeholder="Ex: volei">
        <button class="btn" onclick="addSport()">Adauga</button>
      </div>
      <div class="card">
        <h2>Jucatori</h2>
        <div id="players"></div>
      </div>
    </section>

    <section class="section" id="sec-search">
      <div class="card">
        <h2>Cauta parteneri</h2>
        <p class="hint">Jucatori cu acelasi sport, ordonati dupa compatibilitate.</p>
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
        <label>Apreciezi</label>
        <select id="likeTo"></select>
        <button class="btn" onclick="like()">Apreciaza</button>
      </div>
      <div class="card">
        <h2>Potrivirile tale</h2>
        <button class="btn accent" onclick="showMatches()">Arata potrivirile</button>
        <div id="matches"></div>
      </div>
    </section>

    <section class="section" id="sec-record">
      <div class="card">
        <h2>Inregistreaza un meci</h2>
        <p class="hint">Punctele se calculeaza automat in functie de niveluri.</p>
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

  <nav>
    <button class="active" onclick="go('profiles', this)">Profil</button>
    <button onclick="go('search', this)">Cauta</button>
    <button onclick="go('matches', this)">Potriviri</button>
    <button onclick="go('record', this)">Meci</button>
  </nav>
</div>

<div id="msg" class="toast"></div>

<script>
let token = localStorage.getItem('sm_token') || '';
let pending = '';
let me = null;
let players = [];
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
  const card = document.querySelector('.screen.active .auth-card');
  if (card) {
    card.classList.remove('shake');
    void card.offsetWidth;
    card.classList.add('shake');
    setTimeout(() => card.classList.remove('shake'), 500);
  }
}

function show(name) {
  document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
  document.getElementById('screen-' + name).classList.add('active');
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
  const opts = { method: method || 'GET', headers: { 'X-Token': token } };
  if (data) {
    opts.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    opts.body = new URLSearchParams(data).toString();
  }
  const r = await fetch('/api/' + path, opts);
  const j = await r.json();
  if (r.status === 401) {
    logoutLocal();
    throw new Error(j.message || 'Trebuie sa te autentifici.');
  }
  if (!j.ok) throw new Error(j.message);
  return j;
}

function logoutLocal() {
  token = '';
  me = null;
  localStorage.removeItem('sm_token');
  show('login');
}

async function register() {
  try {
    const j = await api('register', 'POST', {
      username: v('regUser'), email: v('regEmail'), phone: v('regPhone'),
      password: document.getElementById('regPwd').value,
      sports: v('regSports'), availability: v('regAvail'), zone: v('regZone')
    });
    document.getElementById('regPwd').value = '';
    showMsg(j.message);
    show('login');
  } catch (e) { fail(e); }
}

async function login() {
  try {
    const j = await api('login', 'POST', {
      identifier: v('loginId'), password: document.getElementById('loginPwd').value
    });
    pending = j.pending;
    document.getElementById('codePhone').textContent = j.phone;
    document.getElementById('code').value = '';
    show('code');
    showMsg(j.message);
  } catch (e) { fail(e); }
}

async function verifyCode() {
  try {
    const j = await api('verify', 'POST', { pending: pending, code: v('code') });
    token = j.token;
    localStorage.setItem('sm_token', token);
    document.getElementById('loginPwd').value = '';
    await enterApp();
  } catch (e) { fail(e); }
}

async function enterApp() {
  me = await api('me');
  document.getElementById('hello').textContent = 'Salut, ' + me.username + '!';
  show('app');
  loadPlayers();
}

async function logout() {
  try { await api('logout', 'POST'); } catch (e) { /* ignoram */ }
  logoutLocal();
}

async function loadPlayers() {
  try {
    const j = await api('players');
    players = j.items;
    const list = document.getElementById('players');
    list.innerHTML = players.length ? '' : '<div class="empty">Nu exista jucatori inca.</div>';
    players.forEach(p => {
      const chips = p.sports.map(s => '<span class="chip">' + esc(s.sport) + ' · ' + esc(s.level) + ' (' + s.points + ')</span>').join('');
      const mine = me && p.id === me.playerId ? ' (tu)' : '';
      list.innerHTML += '<div class="item"><div class="name">' + esc(p.name) + esc(mine) + '</div>' +
        '<div class="meta">' + chips + '<br>' + esc(p.zone) + ' · ' + esc(p.availability) + '</div></div>';
    });
    const sel = document.getElementById('likeTo');
    const old = sel.value;
    sel.innerHTML = players.filter(p => !me || p.id !== me.playerId)
      .map(p => '<option value="' + p.id + '">' + esc(p.name) + '</option>').join('');
    if (old) sel.value = old;
  } catch (e) { fail(e); }
}

async function addSport() {
  try {
    const j = await api('sports', 'POST', { sport: v('newSport') });
    document.getElementById('newSport').value = '';
    showMsg(j.message);
    loadPlayers();
  } catch (e) { fail(e); }
}

async function search() {
  try {
    const j = await api('search?sport=' + encodeURIComponent(v('searchSport')));
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
    const j = await api('like', 'POST', { to: v('likeTo') });
    showMsg(j.message);
  } catch (e) { fail(e); }
}

async function showMatches() {
  try {
    const j = await api('matches');
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
      sport: v('recordSport'), won: v('result'),
      teammates: v('teammates'), opponents: v('opponents')
    });
    document.getElementById('recordResult').textContent = j.message;
    ['recordSport', 'teammates', 'opponents'].forEach(id => document.getElementById(id).value = '');
    loadPlayers();
  } catch (e) { fail(e); }
}

(async function init() {
  if (!token) { show('login'); return; }
  try { await enterApp(); } catch (e) { logoutLocal(); }
})();
</script>
</body>
</html>
        """;

    record Player(int id, String name, String availability, String zone) {}
    record Scored(Player player, int points, int score) {}
    record Session(int userId, long expires) {}
    record Pending(int userId, String code, long expires, int attempts) {}

    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static final Map<String, Pending> pendings = new ConcurrentHashMap<>();

    private static final Properties cfg = new Properties();
    private static int port;
    private static Connection db;
    private static HttpServer server;
    private static JLabel smsLabel;

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
        if (Files.exists(CFG_FILE)) {
            try (Reader r = Files.newBufferedReader(CFG_FILE, StandardCharsets.UTF_8)) {
                cfg.load(r);
            }
        } else {
            cfg.setProperty("port", "8080");
            cfg.setProperty("twilio_sid", "");
            cfg.setProperty("twilio_token", "");
            cfg.setProperty("twilio_from", "");
            try (Writer w = Files.newBufferedWriter(CFG_FILE, StandardCharsets.UTF_8)) {
                cfg.store(w, "SportMatch - setari. Pentru SMS real completeaza twilio_sid, twilio_token si twilio_from. "
                        + "Daca lipsesc, codurile SMS apar in consola si in fereastra serverului (mod test).");
            }
        }
        String p = cfgValue("port");
        port = Integer.parseInt(p.isEmpty() ? "8080" : p);
    }

    private static String cfgValue(String key) {
        return cfg.getProperty(key, "").trim();
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
            Map<String, String> params = parse(ex.getRequestURI().getRawQuery());
            if (method.equals("POST")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                params.putAll(parse(body));
            }
            String json = api(method, path, params, ex.getRequestHeaders().getFirst("X-Token"));
            send(ex, 200, JSON, json);
        } catch (SecurityException e) {
            send(ex, 401, JSON, error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, JSON, error(e.getMessage()));
        } catch (Exception e) {
            send(ex, 500, JSON, error("Eroare server: " + e.getMessage()));
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

    private static String api(String method, String path, Map<String, String> p, String token) throws Exception {
        String route = method + " " + path;
        switch (route) {
            case "POST /api/register": return register(p);
            case "POST /api/login": return login(p);
            case "POST /api/verify": return verify(p);
            default: break;
        }
        // de aici incolo: doar cu sesiune valida
        int userId = requireSession(token);
        int me = scalarInt("SELECT player_id FROM users WHERE id = ?", userId);
        return switch (route) {
            case "POST /api/logout" -> {
                sessions.remove(token);
                yield ok("Te-ai deconectat.");
            }
            case "GET /api/me" -> meJson(userId, me);
            case "GET /api/players" -> playersJson();
            case "POST /api/sports" -> addSport(me, p);
            case "GET /api/search" -> search(me, p);
            case "POST /api/like" -> like(me, p);
            case "GET /api/matches" -> matches(me);
            case "POST /api/match" -> recordMatch(me, p);
            default -> throw new IllegalArgumentException("Ruta necunoscuta.");
        };
    }

    // =====================================================================
    //  Conturi, parole, SMS, sesiuni
    // =====================================================================

    private static int requireSession(String token) {
        Session s = token == null ? null : sessions.get(token);
        if (s == null) throw new SecurityException("Trebuie sa te autentifici.");
        if (s.expires() < System.currentTimeMillis()) {
            sessions.remove(token);
            throw new SecurityException("Sesiunea a expirat. Autentifica-te din nou.");
        }
        return s.userId();
    }

    private static String register(Map<String, String> p) throws SQLException {
        String username = req(p, "username");
        String email = req(p, "email");
        String phone = req(p, "phone");
        String password = p.getOrDefault("password", "");
        String availability = p.getOrDefault("availability", "").trim();
        String zone = p.getOrDefault("zone", "").trim();

        if (!username.matches("[A-Za-z0-9_.]{3,20}")) {
            throw new IllegalArgumentException("Utilizatorul trebuie sa aiba 3-20 caractere: litere, cifre, _ sau .");
        }
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Adresa de email nu este valida.");
        }
        if (!phone.matches("\\+[0-9]{8,15}")) {
            throw new IllegalArgumentException("Telefonul trebuie in format international, de exemplu +40712345678.");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("Parola trebuie sa aiba cel putin 8 caractere.");
        }
        Set<String> sports = parseSports(p.getOrDefault("sports", ""));
        if (sports.isEmpty()) throw new IllegalArgumentException("Introdu cel putin un sport.");
        if (scalarInt("SELECT COUNT(*) FROM users WHERE username = ? OR email = ?", username, email) > 0) {
            throw new IllegalArgumentException("Exista deja un cont cu acest utilizator sau email.");
        }
        if (findPlayerByName(username) != null) {
            throw new IllegalArgumentException("Exista deja un profil cu acest nume.");
        }

        db.setAutoCommit(false);
        try {
            exec("INSERT INTO players (name, availability, zone) VALUES (?, ?, ?)", username, availability, zone);
            int playerId = findPlayerByName(username).id();
            for (String s : sports) {
                exec("INSERT INTO player_sports (player_id, sport, points) VALUES (?, ?, 0)", playerId, s);
            }
            exec("INSERT INTO users (username, email, phone, password_hash, player_id) VALUES (?, ?, ?, ?, ?)",
                    username, email, phone, hashPassword(password), playerId);
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
        return ok("Contul a fost creat. Acum te poti autentifica.");
    }

    private static String login(Map<String, String> p) throws Exception {
        String identifier = req(p, "identifier");
        String password = p.getOrDefault("password", "");

        int userId = -1;
        String hash = null;
        String phone = null;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id, password_hash, phone FROM users WHERE username = ? OR email = ?")) {
            ps.setString(1, identifier);
            ps.setString(2, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    userId = rs.getInt(1);
                    hash = rs.getString(2);
                    phone = rs.getString(3);
                }
            }
        }
        if (hash == null || !checkPassword(password, hash)) {
            throw new IllegalArgumentException("Utilizator sau parola gresite.");
        }
        String code = String.format("%06d", RND.nextInt(1_000_000));
        sendSms(phone, "Codul tau SportMatch este " + code + ". Expira in 5 minute.");
        String pendingToken = randomToken();
        pendings.put(pendingToken, new Pending(userId, code, System.currentTimeMillis() + CODE_MS, 0));
        return "{\"ok\":true,\"message\":" + q("Ti-am trimis un cod SMS.")
                + ",\"pending\":" + q(pendingToken)
                + ",\"phone\":" + q(maskPhone(phone)) + "}";
    }

    private static String verify(Map<String, String> p) {
        String pendingToken = req(p, "pending");
        String code = req(p, "code");
        Pending pd = pendings.get(pendingToken);
        if (pd == null) throw new IllegalArgumentException("Sesiunea de autentificare a expirat. Incearca din nou.");
        if (pd.expires() < System.currentTimeMillis()) {
            pendings.remove(pendingToken);
            throw new IllegalArgumentException("Codul a expirat. Autentifica-te din nou.");
        }
        if (!pd.code().equals(code)) {
            int attempts = pd.attempts() + 1;
            if (attempts >= MAX_ATTEMPTS) {
                pendings.remove(pendingToken);
                throw new IllegalArgumentException("Prea multe greseli. Autentifica-te din nou.");
            }
            pendings.put(pendingToken, new Pending(pd.userId(), pd.code(), pd.expires(), attempts));
            throw new IllegalArgumentException("Cod gresit. Mai ai " + (MAX_ATTEMPTS - attempts) + " incercari.");
        }
        pendings.remove(pendingToken);
        String token = randomToken();
        sessions.put(token, new Session(pd.userId(), System.currentTimeMillis() + SESSION_MS));
        return "{\"ok\":true,\"message\":" + q("Autentificare reusita.") + ",\"token\":" + q(token) + "}";
    }

    private static String meJson(int userId, int me) throws SQLException {
        String username = scalarString("SELECT username FROM users WHERE id = ?", userId);
        return "{\"ok\":true,\"username\":" + q(username) + ",\"playerId\":" + me + ",\"message\":\"\"}";
    }

    private static String maskPhone(String phone) {
        int n = phone.length();
        return phone.substring(0, Math.min(3, n)) + "******" + phone.substring(n - 3);
    }

    private static void sendSms(String to, String text) throws IOException, InterruptedException {
        String sid = cfgValue("twilio_sid");
        String token = cfgValue("twilio_token");
        String from = cfgValue("twilio_from");
        if (sid.isEmpty() || token.isEmpty() || from.isEmpty()) {
            System.out.println("[SMS mod test] catre " + to + ": " + text);
            if (smsLabel != null) {
                SwingUtilities.invokeLater(() -> smsLabel.setText("Mod test SMS. Ultimul mesaj: " + text));
            }
            return;
        }
        String body = "To=" + enc(to) + "&From=" + enc(from) + "&Body=" + enc(text);
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json"))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalArgumentException("Nu am putut trimite SMS-ul (cod " + resp.statusCode()
                    + "). Verifica setarile Twilio din sportmatch.cfg.");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        RND.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RND.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, PBKDF2_ITER);
        return PBKDF2_ITER + ":" + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(hash);
    }

    private static boolean checkPassword(String password, String stored) {
        String[] parts = stored.split(":");
        int iters = Integer.parseInt(parts[0]);
        byte[] salt = Base64.getDecoder().decode(parts[1]);
        byte[] expected = Base64.getDecoder().decode(parts[2]);
        byte[] actual = pbkdf2(password.toCharArray(), salt, iters);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // =====================================================================
    //  Functionalitate (profilul utilizatorului autentificat)
    // =====================================================================

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

    private static String addSport(int me, Map<String, String> p) throws SQLException {
        String sport = req(p, "sport").toLowerCase();
        exec("INSERT OR IGNORE INTO player_sports (player_id, sport, points) VALUES (?, ?, 0)", me, sport);
        return ok("Sportul " + sport + " a fost adaugat in profil.");
    }

    private static String search(int me, Map<String, String> p) throws SQLException {
        String sport = req(p, "sport").toLowerCase();
        if (!hasSport(me, sport)) {
            throw new IllegalArgumentException("Sportul nu este in profilul tau. Adauga-l mai intai.");
        }
        int myLevel = levelFromPoints(pointsOf(me, sport));
        Player meP = playerById(me);

        List<Scored> results = new ArrayList<>();
        for (Player c : playersWithSport(sport, me)) {
            int pts = pointsOf(c.id(), sport);
            results.add(new Scored(c, pts, matchScore(meP, myLevel, c, levelFromPoints(pts))));
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

    private static String like(int me, Map<String, String> p) throws SQLException {
        int to = intParam(p, "to");
        if (to == me) throw new IllegalArgumentException("Nu te poti aprecia pe tine.");
        Player target = playerById(to);
        exec("INSERT OR IGNORE INTO likes (from_id, to_id) VALUES (?, ?)", me, to);
        if (isMutual(me, to)) {
            return ok("Potrivire! Tu si " + target.name() + " va puteti contacta pentru un joc.");
        }
        return ok("Aprecierea a fost salvata. Te vom anunta cand si celalalt apreciaza.");
    }

    private static String matches(int me) throws SQLException {
        Set<String> mySports = sportPoints(me).keySet();
        List<String> items = new ArrayList<>();
        for (Player o : matchesFor(me)) {
            Set<String> common = new TreeSet<>(mySports);
            common.retainAll(sportPoints(o.id()).keySet());
            items.add("{\"id\":" + o.id() + ",\"name\":" + q(o.name())
                    + ",\"common\":" + q(common.isEmpty() ? "-" : join(common))
                    + ",\"zone\":" + q(o.zone())
                    + ",\"availability\":" + q(o.availability()) + "}");
        }
        return okItems(items);
    }

    private static String recordMatch(int me, Map<String, String> p) throws SQLException {
        String sport = req(p, "sport").toLowerCase();
        if (!hasSport(me, sport)) {
            throw new IllegalArgumentException("Sportul nu este in profilul tau. Adauga-l mai intai.");
        }
        boolean won = "1".equals(p.get("won"));

        List<Player> teammates = resolveNames(p.getOrDefault("teammates", ""), me, sport);
        List<Player> opponents = resolveNames(p.getOrDefault("opponents", ""), me, sport);
        if (opponents.isEmpty()) throw new IllegalArgumentException("Trebuie sa introduci cel putin un adversar.");

        int ownLevel = levelFromPoints(pointsOf(me, sport));
        double teamAvg = teammates.isEmpty() ? ownLevel : averageLevel(teammates, sport);
        double oppAvg = averageLevel(opponents, sport);
        int delta = computePoints(won, ownLevel, teamAvg, oppAvg);

        int oldPts = pointsOf(me, sport);
        int newPts = Math.max(0, oldPts + delta);
        exec("UPDATE player_sports SET points = ? WHERE player_id = ? AND sport = ?", newPts, me, sport);
        exec("INSERT INTO match_log (player_id, sport, won, points_delta, teammates, opponents) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                me, sport, won ? 1 : 0, delta, namesOf(teammates), namesOf(opponents));

        String text = String.format("Meci inregistrat: %s, %+d puncte. Total %s: %d puncte.",
                won ? "victorie" : "infrangere", delta, sport, newPts);
        int oldLevel = levelFromPoints(oldPts);
        int newLevel = levelFromPoints(newPts);
        if (newLevel > oldLevel) text += " Felicitari! Ai urcat la nivelul " + LEVELS[newLevel] + ".";
        if (newLevel < oldLevel) text += " Ai coborat la nivelul " + LEVELS[newLevel] + ".";
        return ok(text);
    }

    /** Jucatorii din lista; arunca eroare daca lista contine ceva invalid. */
    private static List<Player> resolveNames(String raw, int me, String sport) throws SQLException {
        List<Player> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        for (String n : raw.split(",")) {
            String name = n.trim();
            if (name.isEmpty()) continue;
            Player p = findPlayerByName(name);
            if (p == null) throw new IllegalArgumentException("Nu exista profilul \"" + name + "\".");
            if (p.id() == me) throw new IllegalArgumentException("Nu te poti include singur in lista.");
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
    //  Logica de baza (identica cu versiunea anterioara)
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

    /** Victorie: 10 + 5*oppDiff + 3*teamDiff, minim +3. Infrangere: -(8 - 4*oppDiff + 2*teamDiff), maxim -1. */
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
    //  Ajutoare HTTP / JSON
    // =====================================================================

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

    private static String scalarString(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
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

    private static Player playerById(int id) throws SQLException {
        List<Player> found = queryPlayers("SELECT id, name, availability, zone FROM players WHERE id = ?", id);
        if (found.isEmpty()) throw new IllegalArgumentException("Profilul nu exista.");
        return found.get(0);
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

        JLabel info = new JLabel("<html><b>Serverul SportMatch ruleaza.</b><br><br>"
                + "Pe acest calculator: http://localhost:" + port + "<br>"
                + "Pe aceeasi retea Wi-Fi: http://" + localIp() + ":" + port + "<br><br>"
                + "Baza de date: " + DB_FILE.toAbsolutePath() + "</html>");

        smsLabel = new JLabel(cfgValue("twilio_sid").isEmpty()
                ? "SMS: mod test (codurile apar aici)"
                : "SMS: Twilio activ");

        JButton stop = new JButton("Opreste serverul");
        stop.addActionListener(e -> System.exit(0));

        JPanel center = new JPanel(new GridLayout(0, 1, 0, 10));
        center.add(info);
        center.add(smsLabel);

        JPanel p = new JPanel(new BorderLayout(12, 12));
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        p.add(center, BorderLayout.CENTER);
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
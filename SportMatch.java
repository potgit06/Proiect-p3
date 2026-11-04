import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * SportMatch - server pe calculator cu conturi, autentificare cu utilizator si parola.
 * Fisiere langa program: sportmatch.db (date), sportmatch.cfg (setari server).
 */
public class SportMatch {

    private static final Path CFG_FILE = Paths.get("sportmatch.cfg");
    private static final Path DB_FILE = Paths.get("sportmatch.db");
    private static final String[] LEVELS = {"Incepator", "Intermediar", "Avansat"};
    private static final int[] LEVEL_MIN_POINTS = {0, 50, 150};
    private static final long SESSION_MS = 30L * 24 * 3600 * 1000;
    private static final int PBKDF2_ITER = 120_000;
    private static final SecureRandom RND = new SecureRandom();
    private static final String JSON = "application/json; charset=utf-8";

    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS players (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            name         TEXT NOT NULL UNIQUE COLLATE NOCASE,
            availability TEXT NOT NULL,
            zone         TEXT NOT NULL,
            avatar       TEXT NOT NULL DEFAULT '',
            bio          TEXT NOT NULL DEFAULT '')
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
        """,
        """
        CREATE TABLE IF NOT EXISTS posts (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            player_id  INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
            sport      TEXT NOT NULL,
            title      TEXT NOT NULL,
            place      TEXT NOT NULL,
            when_text  TEXT NOT NULL,
            slots      INTEGER NOT NULL DEFAULT 0,
            body       TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')))
        """,
        """
        CREATE TABLE IF NOT EXISTS post_joins (
            post_id   INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
            player_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
            PRIMARY KEY (post_id, player_id))
        """,
        """
        CREATE TABLE IF NOT EXISTS meta (
            key   TEXT PRIMARY KEY,
            value TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS geo_cache (
            key  TEXT PRIMARY KEY,
            lat  REAL NOT NULL,
            lon  REAL NOT NULL,
            addr TEXT NOT NULL DEFAULT '')
        """,
        """
        CREATE TABLE IF NOT EXISTS route_cache (
            key     TEXT PRIMARY KEY,
            km      REAL NOT NULL,
            minutes INTEGER NOT NULL,
            coords  TEXT NOT NULL DEFAULT '[]')
        """,
        """
        CREATE TABLE IF NOT EXISTS messages (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            from_id    INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
            to_id      INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
            body       TEXT NOT NULL,
            seen       INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')))
        """,
        """
        CREATE TABLE IF NOT EXISTS notifications (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            player_id  INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
            kind       TEXT NOT NULL,
            other_id   INTEGER,
            text       TEXT NOT NULL,
            seen       INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')))
        """
    };

    /** Profile fictive adaugate automat la prima pornire: nume, zona, disponibilitate, descriere, sporturi. */
    private static final String[][] DEMO_PLAYERS = {
        {"andrei.munteanu", "Fabric", "seara, dupa 18:00",
         "Mijlocas, joc fotbal de 10 ani. Organizez meciuri de 5 la 5 in fiecare miercuri.", "fotbal, tenis"},
        {"ioana.dragomir", "Complexul Studentesc", "weekend dimineata",
         "Baschet si alergare. Caut echipa pentru liga de cartier.", "baschet, alergare"},
        {"vlad.pop", "Girocului", "luni si joi seara",
         "Portar. Mai bine un meci slab decat o seara pe canapea.", "fotbal"},
        {"maria.ionescu", "Centru", "marti, joi, 19:00",
         "Antrenor de volei la o echipa de juniori. Joc si pe plaja vara.", "volei, tenis"},
        {"stefan.radu", "Dumbravita", "weekend oricand",
         "Fotbal de 7 la 7, teren cu gazon sintetic. Vin cu mingi si vestele.", "fotbal, padel"},
        {"alexandra.neagu", "Soarelui", "miercuri si duminica",
         "Tenis de la 14 ani, acum joc de placere. Caut partener de dublu.", "tenis"},
        {"cosmin.barbu", "Mehala", "seara, dupa 20:00",
         "Baschet 3x3 in parc. Joc orice vreme, cat timp nu ploua cu gheata.", "baschet, fotbal"},
        {"raluca.serban", "Iosefin", "sambata dimineata",
         "Alerg semimaratoane si joc badminton. Prefer grupuri mici.", "alergare, badminton"},
        {"george.tanase", "Lipovei", "vineri seara",
         "Fotbal in sala, echipa are nevoie mereu de inca un om.", "fotbal"},
        {"diana.lupu", "Elisabetin", "luni, miercuri, vineri",
         "Volei si padel. Imi place jocul rapid si fara certuri la fileu.", "volei, padel"},
        {"mihai.constantin", "Aradului", "weekend dupa-amiaza",
         "Padel si tenis. Rachete de rezerva am mereu la mine.", "padel, tenis"},
        {"elena.vasile", "Torontalului", "marti si duminica",
         "Badminton de performanta in liceu, acum joc relaxat. Si ping-pong.", "badminton, tenis"}
    };

    /** Anunturi fictive: indexul autorului in DEMO_PLAYERS, sport, titlu, loc, cand, locuri, text. */
    private static final String[][] DEMO_POSTS = {
        {"0", "fotbal", "Meci de fotbal 5 la 5, miercuri seara", "Baza sportiva Fabric", "Miercuri, 19:00", "3",
         "Avem 7 confirmati din 10. Teren cu gazon sintetic, 20 lei de om. Vestele le aduc eu."},
        {"2", "fotbal", "Caut portar pentru meci de 7 la 7", "Teren Girocului", "Joi, 20:30", "1",
         "Portarul nostru s-a accidentat. Nivel mediu, jucam fara faulturi dure."},
        {"1", "baschet", "Baschet 3x3 in parc, sambata", "Parcul Studentesc", "Sambata, 10:00", "2",
         "Doua echipe formate, mai e loc de inca doi. Venim cu mingea si apa."},
        {"4", "fotbal", "Fotbal 7 la 7 duminica dimineata", "Complex Dumbravita", "Duminica, 09:30", "4",
         "Meci amical, fara arbitru. 25 lei de persoana pentru teren."},
        {"3", "volei", "Volei mixt in sala, marti", "Sala Olimpia", "Marti, 19:00", "5",
         "Echipe mixte, nivel incepator-mediu. Avem nevoie de minim 10 oameni."},
        {"5", "tenis", "Partener pentru dublu la tenis", "Clubul Soarelui", "Miercuri, 18:00", "1",
         "Joc de 2 ani constant, nivel intermediar. Terenul e rezervat deja."},
        {"6", "baschet", "Baschet seara pe teren iluminat", "Teren Mehala", "Vineri, 20:00", "4",
         "Jucam pana se face 22:00. Toata lumea e binevenita, chiar si incepatori."},
        {"8", "fotbal", "Fotbal in sala, vineri seara", "Sala Lipovei", "Vineri, 21:00", "2",
         "Grup stabil de 8 persoane, ne mai trebuie doi. Jucam saptamanal."},
        {"7", "alergare", "Alergare lunga de sambata", "Parcul Rozelor", "Sambata, 08:00", "6",
         "15 km in ritm relaxat, 5:30 pe km. Ne oprim la cafea dupa."},
        {"9", "padel", "Padel dublu, doi jucatori liberi", "Padel Club Elisabetin", "Luni, 18:30", "2",
         "Avem terenul rezervat o ora si jumatate. Nivel mediu."},
        {"10", "tenis", "Meci de tenis simplu, nivel avansat", "Baza Aradului", "Duminica, 17:00", "1",
         "Caut adversar care sa alerge dupa fiecare minge. Doua seturi plus tie-break."},
        {"11", "badminton", "Badminton dublu mixt", "Sala Torontalului", "Marti, 19:30", "3",
         "Fluturasi si rachete de rezerva avem. 15 lei de om."},
        {"0", "tenis", "Tenis dublu, cautam o pereche", "Clubul Central", "Sambata, 16:00", "2",
         "Suntem doi, cautam alti doi cu nivel apropiat. Terenul cu zgura."},
        {"3", "volei", "Volei pe plaja la bazin", "Bazin Termal", "Duminica, 11:00", "4",
         "Nisip, umbrela si multa apa. Jucam 4 la 4, fara punctaj strict."},
        {"4", "fotbal", "Meci de fotbal cu echipa din cartier", "Teren Dumbravita 2", "Miercuri, 20:00", "5",
         "Ne lipsesc 5 oameni, au anulat jumatate din echipa. Nivel lejer."},
        {"1", "alergare", "Antrenament de interval pe pista", "Stadionul Dan Paltinisanu", "Joi, 18:00", "4",
         "10 x 400 m cu pauza de 90 secunde. Buna pentru cine pregateste un 10K."},
        {"6", "fotbal", "Fotbal 5 la 5 duminica seara", "Teren Mehala", "Duminica, 19:00", "3",
         "Ultimul meci din luna, apoi ne mutam in sala. Venim cu doua mingi."},
        {"9", "volei", "Volei in sala, nivel mediu", "Sala Elisabetin", "Vineri, 18:00", "6",
         "Ne trebuie inca sase persoane pentru doua echipe complete."},
        {"10", "padel", "Padel pentru incepatori", "Padel Club Aradului", "Sambata, 12:00", "3",
         "Explic regulile pe teren, nu e nevoie de experienta. Rachete incluse."},
        {"11", "badminton", "Badminton simplu, dupa-amiaza", "Sala Torontalului", "Duminica, 15:00", "1",
         "Caut un adversar pentru trei seturi. Terenul e platit."},
        {"2", "fotbal", "Meci amical intre doua cartiere", "Teren Giroc", "Sambata, 18:00", "6",
         "Giroc contra Fabric. Avem nevoie de rezerve, meciul dureaza 90 de minute."},
        {"5", "tenis", "Antrenament la perete si meci scurt", "Clubul Soarelui", "Joi, 17:00", "1",
         "O ora de exercitii, apoi un set. Bun pentru cine reia tenisul."},
        {"7", "badminton", "Badminton dublu, seara", "Sala Iosefin", "Miercuri, 20:00", "2",
         "Grup mic si liniste, fara muzica tare. Jucam doua ore."},
        {"8", "fotbal", "Fotbal pe teren descoperit", "Teren Lipovei", "Duminica, 10:00", "4",
         "Daca ploua, mutam meciul in sala de langa. Anunt cu o ora inainte."}
    };

    private static final String PAGE_HEAD = """
<!DOCTYPE html>
<html lang="ro">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#1e6f5c">
<title>SportMatch</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
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
  --soft: #eef4f1;        /* fundal discret: chips, statistici, etichete */
  --soft-text: #15503f;
  --item: #fbfdfc;
  --body-text: #3a4a46;
  --nav-bg: rgba(255, 255, 255, 0.94);
  --shadow: rgba(0, 0, 0, 0.05);
  --auth: linear-gradient(-45deg, #1e6f5c, #15503f, #2a8c73, #0f3d31);
}
/* tema intunecata */
:root[data-theme="dark"] {
  --main: #3fae8f;
  --main-dark: #2f8c72;
  --accent: #f4a259;
  --bg: #101e1a;
  --card: #182c27;
  --text: #e7f0ed;
  --muted: #93a8a2;
  --line: #26413a;
  --soft: #1f352f;
  --soft-text: #9fe0cb;
  --item: #1c312b;
  --body-text: #c3d4cf;
  --nav-bg: rgba(24, 44, 39, 0.94);
  --shadow: rgba(0, 0, 0, 0.35);
  --auth: linear-gradient(-45deg, #15332b, #0b1b17, #1d4639, #0a1512);
}
html { background: var(--bg); }
* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  color: var(--text);
  background: var(--bg);
}
.screen { display: none; }
/* fara transform aici: ar impiedica bara de jos si butonul + sa ramana fixate pe ecran */
.screen.active { display: block; animation: fadeScreen 0.45s ease both; }
@keyframes fadeScreen {
  from { opacity: 0; }
  to { opacity: 1; }
}
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
  background: var(--auth);
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
  background: var(--card);
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

/* ---------- buton tema ---------- */
.theme-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 0;
}
.theme-btn svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform 0.35s ease;
}
.theme-btn:active svg { transform: rotate(-25deg) scale(0.9); }
.head-right { display: flex; align-items: center; gap: 8px; }
.head-right .theme-btn { width: 36px; height: 34px; }
.auth-theme {
  position: fixed;
  top: 14px;
  right: 14px;
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.38);
  color: #fff;
  z-index: 8;
}
.auth-theme svg { width: 21px; height: 21px; }
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
  background: var(--card);
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
main { padding: 16px 14px 118px; max-width: 640px; margin: 0 auto; }
.section { display: none; }
.section.active { display: block; animation: fadeIn 0.35s ease both; }
.card {
  background: var(--card);
  border-radius: 16px;
  padding: 16px;
  margin-bottom: 14px;
  box-shadow: 0 2px 10px var(--shadow);
}
.card h2 { margin: 0 0 6px; font-size: 17px; font-weight: 650; }
.hint { margin: 0 0 8px; font-size: 13px; color: var(--muted); }
.item {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 14px;
  margin-top: 10px;
  background: var(--item);
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
  background: var(--soft);
  color: var(--soft-text);
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
  background: var(--card);
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
  background: var(--soft);
  color: var(--soft-text);
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
  background: var(--nav-bg);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid var(--line);
  box-shadow: 0 -4px 18px rgba(21, 80, 63, 0.07);
  display: flex;
  gap: 2px;
  padding: 7px 7px calc(7px + env(safe-area-inset-bottom, 0px));
  z-index: 5;
}
nav button {
  flex: 1;
  border: none;
  background: none;
  border-radius: 14px;
  padding: 7px 2px 6px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.2px;
  color: var(--muted);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  transition: color 0.22s, background 0.22s, transform 0.16s;
}
nav button svg {
  width: 23px;
  height: 23px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform 0.25s ease;
}
nav button:hover { color: var(--main); }
nav button:active { transform: scale(0.93); }
nav button.active { color: var(--main); background: var(--soft); }
nav button.active svg { transform: translateY(-2px) scale(1.1); }
nav button.active span { font-weight: 700; }

/* ---------- avatar ---------- */
.ava {
  border-radius: 50%;
  object-fit: cover;
  background: var(--main);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  flex: none;
  overflow: hidden;
  text-transform: uppercase;
}
.ava img { width: 100%; height: 100%; object-fit: cover; display: block; }

/* ---------- pagina de profil ---------- */
.profile-top { display: flex; align-items: center; gap: 18px; }
.profile-top .ava { width: 92px; height: 92px; font-size: 32px; box-shadow: 0 4px 14px rgba(0,0,0,0.12); }
.profile-id { flex: 1; min-width: 0; }
.profile-id h2 { margin: 0; font-size: 20px; word-break: break-word; }
.profile-id .where { margin: 3px 0 0; font-size: 13px; color: var(--muted); }
.stats { display: flex; gap: 6px; margin-top: 16px; text-align: center; }
.stats div { flex: 1; padding: 10px 4px; background: var(--soft); border-radius: 12px; }
.stats b { display: block; font-size: 18px; }
.stats span { font-size: 11px; color: var(--muted); }
.bio { margin: 14px 0 0; font-size: 14px; line-height: 1.5; white-space: pre-line; }
.bio.none { color: var(--muted); font-style: italic; }
.row { display: flex; gap: 8px; margin-top: 14px; }
.row .btn { margin-top: 0; }
.btn.ghost { background: var(--card); color: var(--main); border: 1px solid var(--main); }
.btn.ghost:hover { background: var(--soft); }
textarea {
  width: 100%;
  min-height: 92px;
  padding: 12px 13px;
  border: 1px solid var(--line);
  border-radius: 12px;
  font-size: 15px;
  font-family: inherit;
  color: var(--text);
  resize: vertical;
  background: var(--card);
}
textarea:focus { outline: none; border-color: var(--main); box-shadow: 0 0 0 3px rgba(30,111,92,0.15); }
.editor { display: none; }
.editor.open { display: block; }
.pick { display: flex; align-items: center; gap: 14px; margin-top: 8px; }
.pick .ava { width: 72px; height: 72px; font-size: 26px; }
.pick-btns { display: flex; flex-direction: column; gap: 8px; }
.pick-btns button {
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--text);
  border-radius: 10px;
  padding: 8px 14px;
  font-size: 14px;
  cursor: pointer;
}
.pick-btns button:hover { border-color: var(--main); color: var(--main); }
.count { font-size: 12px; color: var(--muted); text-align: right; margin-top: 4px; }

/* ---------- anunturi ---------- */
.post {
  background: var(--card);
  border-radius: 16px;
  padding: 14px 15px 12px;
  margin-bottom: 12px;
  box-shadow: 0 2px 10px var(--shadow);
  animation: popItem 0.35s ease both;
}
.post-head { display: flex; align-items: center; gap: 10px; }
.post-head .ava { width: 42px; height: 42px; font-size: 16px; }
.post-who { flex: 1; min-width: 0; }
.post-who b { font-size: 15px; display: block; cursor: pointer; }
.post-who b:hover { color: var(--main); }
.post-who span { font-size: 12px; color: var(--muted); }
.sport-tag {
  font-size: 11px;
  font-weight: 700;
  background: var(--main);
  color: #fff;
  border-radius: 999px;
  padding: 4px 11px;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  flex: none;
}
.post h3 { margin: 11px 0 6px; font-size: 16px; line-height: 1.35; }
.post p { margin: 0; font-size: 14px; line-height: 1.5; color: var(--body-text); }
.post-meta { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.post-meta span {
  font-size: 12px;
  background: var(--soft);
  color: var(--soft-text);
  border-radius: 8px;
  padding: 5px 10px;
}
.post-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding-top: 11px;
  border-top: 1px solid var(--line);
}
.post-foot .who { flex: 1; font-size: 12px; color: var(--muted); }
.join {
  border: 1px solid var(--main);
  background: var(--main);
  color: #fff;
  border-radius: 10px;
  padding: 8px 16px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.1s;
}
.join:active { transform: scale(0.97); }
.join.off { background: var(--card); color: var(--main); }
.join.own { background: var(--soft); color: var(--muted); border-color: var(--line); cursor: default; }
.filters { display: flex; gap: 7px; overflow-x: auto; padding: 2px 0 8px; -webkit-overflow-scrolling: touch; }
.filters button {
  flex: none;
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--text);
  border-radius: 999px;
  padding: 8px 15px;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.filters button.on { background: var(--main); color: #fff; border-color: var(--main); }
.loader { text-align: center; color: var(--muted); font-size: 13px; padding: 14px 0 6px; }
.fab {
  position: fixed;
  right: 16px;
  bottom: calc(86px + env(safe-area-inset-bottom, 0px));
  width: 54px;
  height: 54px;
  border-radius: 50%;
  border: none;
  background: var(--accent);
  color: #3b2a12;
  font-size: 30px;
  line-height: 1;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(0,0,0,0.25);
  z-index: 6;
  display: none;
}
.fab.show { display: block; }

/* ---------- harta si traseul ---------- */
.mapbox {
  position: relative;
  z-index: 0;
  height: 270px;
  margin: 10px 0 12px;
  border-radius: 16px;
  overflow: hidden;
  background: var(--soft);
  border: 1px solid var(--line);
}
.mapbox .empty { padding: 36px 14px; }
.leaflet-container { font: inherit; background: var(--soft); }
.trip-nums { display: flex; gap: 8px; margin: 10px 0 4px; }
.trip-nums div {
  flex: 1;
  background: var(--soft);
  border-radius: 14px;
  padding: 10px 6px;
  text-align: center;
}
.trip-nums b { display: block; font-size: 20px; color: var(--soft-text); }
.trip-nums span { font-size: 11px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.04em; }
.trip-line { font-size: 13px; color: var(--body-text); margin: 6px 0; line-height: 1.45; }
.trip-line b { color: var(--soft-text); }
.trip-warn { font-size: 13px; color: #c0392b; margin: 6px 0 0; }
.map-btn {
  border: 1px solid var(--main);
  background: var(--card);
  color: var(--main);
  border-radius: 999px;
  padding: 8px 14px;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.map-btn:hover { background: var(--soft); }


.modes { display: flex; gap: 8px; margin: 10px 0 2px; }
.mode {
  flex: 1;
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--muted);
  border-radius: 999px;
  padding: 9px 8px;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.mode.on { background: var(--main); color: #fff; border-color: var(--main); }
.nav-live { display: none; }
.nav-live.on { display: block; }
.nav-step {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--main);
  color: #fff;
  border-radius: 16px;
  padding: 14px 16px;
  margin-bottom: 10px;
}
.nav-arrow { font-size: 30px; line-height: 1; }
.nav-body { flex: 1; min-width: 0; }
.nav-dist { font-size: 22px; font-weight: 700; white-space: nowrap; }
.nav-text { font-size: 14px; line-height: 1.35; overflow-wrap: anywhere; }
.nav-nums { display: flex; gap: 8px; margin-bottom: 10px; }
.nav-nums div {
  flex: 1;
  background: var(--soft);
  border-radius: 14px;
  padding: 9px 4px;
  text-align: center;
}
.nav-nums div { min-width: 0; }
.nav-nums b { display: block; font-size: 17px; color: var(--soft-text); }
.nav-nums span { font-size: 10px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.04em; }
.nav-apps { display: flex; gap: 8px; margin-top: 10px; }
.nav-apps a { flex: 1; text-align: center; text-decoration: none; padding: 9px 6px; }
.me-dot {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #2176ff;
  border: 3px solid #fff;
  box-shadow: 0 0 0 2px rgba(33, 118, 255, 0.5);
}

/* ---------- clasament ---------- */
.rank-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border-radius: 14px;
  margin-top: 8px;
  background: var(--item);
  border: 1px solid var(--line);
  animation: popItem 0.3s ease both;
}
.rank-row .ava { width: 40px; height: 40px; font-size: 15px; }
.rank-no {
  width: 30px;
  flex: none;
  text-align: center;
  font-weight: 800;
  font-size: 16px;
  color: var(--muted);
}
.rank-row.top1, .rank-row.top2, .rank-row.top3 { border-color: var(--main); background: var(--soft); }
.rank-row.top1 .rank-no, .rank-row.top2 .rank-no, .rank-row.top3 .rank-no { color: var(--main); font-size: 19px; }
.rank-who { flex: 1; min-width: 0; }
.rank-who b { display: block; font-size: 15px; cursor: pointer; }
.rank-who span { font-size: 12px; color: var(--muted); }
.rank-pts { text-align: right; flex: none; }
.rank-pts b { display: block; font-size: 17px; color: var(--main); }
.rank-pts span { font-size: 11px; color: var(--muted); }
.me-row { outline: 2px solid var(--accent); outline-offset: -2px; }

/* ---------- chat ---------- */
.chat-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 14px;
  margin-top: 8px;
  background: var(--item);
  border: 1px solid var(--line);
  cursor: pointer;
}
.chat-row:hover { border-color: var(--main); }
.chat-row .ava { width: 46px; height: 46px; font-size: 17px; }
.chat-who { flex: 1; min-width: 0; }
.chat-who b { display: block; font-size: 15px; }
.chat-who span {
  display: block;
  font-size: 13px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge {
  min-width: 21px;
  height: 21px;
  border-radius: 999px;
  background: var(--accent);
  color: #3b2a12;
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 6px;
  flex: none;
}
.chat-head { display: flex; align-items: center; gap: 11px; margin-bottom: 10px; }
.chat-head .ava { width: 42px; height: 42px; font-size: 16px; }
.chat-head b { font-size: 16px; }
.back {
  border: 1px solid var(--line);
  background: var(--card);
  color: var(--text);
  border-radius: 10px;
  padding: 7px 12px;
  font-size: 14px;
  cursor: pointer;
  flex: none;
}
.thread {
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-height: 160px;
  max-height: 52vh;
  overflow-y: auto;
  padding: 6px 2px;
}
.bubble {
  max-width: 78%;
  padding: 9px 13px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.45;
  word-wrap: break-word;
  animation: popItem 0.25s ease both;
}
.bubble small { display: block; font-size: 10px; opacity: 0.65; margin-top: 3px; }
.bubble.them { align-self: flex-start; background: var(--soft); color: var(--text); border-bottom-left-radius: 5px; }
.bubble.me { align-self: flex-end; background: var(--main); color: #fff; border-bottom-right-radius: 5px; }
.send-row { display: flex; gap: 8px; margin-top: 10px; }
.send-row input { flex: 1; }
.send-row button {
  flex: none;
  border: none;
  background: var(--main);
  color: #fff;
  border-radius: 12px;
  padding: 0 18px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
}

/* ---------- notificari ---------- */
.bell { position: relative; }
.bell .dot {
  position: absolute;
  top: 2px;
  right: 2px;
  min-width: 17px;
  height: 17px;
  border-radius: 999px;
  background: var(--accent);
  color: #3b2a12;
  font-size: 10px;
  font-weight: 800;
  display: none;
  align-items: center;
  justify-content: center;
  padding: 0 4px;
}
.bell.on .dot { display: inline-flex; }
.notif {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 11px 12px;
  border-radius: 14px;
  margin-top: 8px;
  background: var(--item);
  border: 1px solid var(--line);
}
.notif.fresh { border-color: var(--main); background: var(--soft); }
.notif .ava { width: 38px; height: 38px; font-size: 14px; }
.notif div { flex: 1; min-width: 0; }
.notif b { display: block; font-size: 14px; font-weight: 600; }
.notif span { font-size: 12px; color: var(--muted); }
nav button .dot {
  position: absolute;
  top: 4px;
  right: 50%;
  margin-right: -17px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--accent);
  display: none;
}
nav button { position: relative; }
nav button.has-new .dot { display: block; }

/* ---------- cat valoreaza meciul ---------- */
.calc { margin-top: 14px; }
.calc-outcomes { display: flex; gap: 10px; }
.calc-outcomes div {
  flex: 1;
  padding: 12px 10px;
  border-radius: 14px;
  text-align: center;
  border: 1px solid var(--line);
  background: var(--item);
}
.calc-outcomes b { display: block; font-size: 24px; line-height: 1.1; }
.calc-outcomes span { font-size: 11px; color: var(--muted); }
.calc-outcomes .won { border-color: var(--main); background: var(--soft); }
.calc-outcomes .won b { color: var(--main); }
.calc-outcomes .lost b { color: #c7533f; }
:root[data-theme="dark"] .calc-outcomes .lost b { color: #ef8b76; }
.calc-side { margin-top: 12px; }
.calc-side h3 {
  margin: 0 0 5px;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--muted);
}
.calc-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 14px;
  border-bottom: 1px solid var(--line);
}
.calc-line:last-child { border-bottom: none; }
.calc-line .ava { width: 28px; height: 28px; font-size: 11px; }
.calc-line .who { flex: 1; min-width: 0; }
.calc-line .lvl { font-size: 12px; color: var(--muted); flex: none; }
.calc-avg {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--soft);
  font-size: 13px;
  color: var(--soft-text);
}
.calc-warn { margin-top: 10px; font-size: 13px; color: #c7533f; }
:root[data-theme="dark"] .calc-warn { color: #ef8b76; }

/* ---------- cod de recuperare ---------- */
.code-box {
  margin-top: 12px;
  padding: 16px;
  border-radius: 14px;
  background: var(--soft);
  border: 1px dashed var(--main);
  text-align: center;
}
.code-box b {
  display: block;
  font-size: 24px;
  letter-spacing: 2px;
  color: var(--soft-text);
  font-family: ui-monospace, Consolas, monospace;
  margin: 6px 0;
  word-break: break-all;
}
</style>
        """;

    private static final String PAGE_BODY = """
</head>
<body>

<!-- ================= LOGIN ================= -->
<div id="screen-login" class="screen active">
  <div class="auth-bg">
    <button class="theme-btn auth-theme" onclick="toggleTheme()" title="Schimba tema"></button>
    <div class="brand">
      <svg class="logo" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#ffffff"/><path d="M50 36 L63.3 45.7 L58.2 61.3 L41.8 61.3 L36.7 45.7 Z" fill="#1e6f5c"/><path d="M50 36 L50 6 M63.3 45.7 L88 27 M58.2 61.3 L74 84 M41.8 61.3 L26 84 M36.7 45.7 L12 27" stroke="#1e6f5c" stroke-width="3" fill="none" stroke-linecap="round"/></svg>
      <h1>SportMatch</h1>
      <p>Gaseste parteneri de joc in orasul tau</p>
    </div>
    <div class="auth-card" id="card-login">
      <h2>Autentificare</h2>
      <p class="sub">Introdu utilizatorul sau emailul si parola.</p>
      <label>Utilizator sau email</label>
      <input id="loginId" autocomplete="username">
      <label>Parola</label>
      <input id="loginPwd" type="password" autocomplete="current-password">
      <button class="btn" onclick="login()">Continua</button>
      <button class="link" onclick="show('register')">Nu ai cont? Creeaza unul</button>
      <button class="link" onclick="show('recover')">Ai uitat parola?</button>
    </div>
  </div>
</div>

<!-- ================= RECUPERARE PAROLA ================= -->
<div id="screen-recover" class="screen">
  <div class="auth-bg">
    <button class="theme-btn auth-theme" onclick="toggleTheme()" title="Schimba tema"></button>
    <div class="brand">
      <svg class="logo" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#ffffff"/><path d="M50 36 L63.3 45.7 L58.2 61.3 L41.8 61.3 L36.7 45.7 Z" fill="#1e6f5c"/><path d="M50 36 L50 6 M63.3 45.7 L88 27 M58.2 61.3 L74 84 M41.8 61.3 L26 84 M36.7 45.7 L12 27" stroke="#1e6f5c" stroke-width="3" fill="none" stroke-linecap="round"/></svg>
      <h1>Parola noua</h1>
    </div>
    <div class="auth-card" id="card-recover">
      <h2>Recuperare parola</h2>
      <p class="sub">Foloseste codul de recuperare primit cand ti-ai facut contul. Daca nu il mai ai,
        cere unul nou de la persoana care tine serverul pornit.</p>
      <label>Utilizator sau email</label>
      <input id="recId" autocomplete="username">
      <label>Cod de recuperare</label>
      <input id="recCode" placeholder="Ex: ABCD-2345-WXYZ" autocapitalize="characters">
      <label>Parola noua (minim 8 caractere)</label>
      <input id="recPwd" type="password" autocomplete="new-password">
      <button class="btn" onclick="recoverPassword()">Schimba parola</button>
      <button class="link" onclick="show('login')">Inapoi la autentificare</button>
    </div>
  </div>
</div>

<!-- ================= COD DE RECUPERARE ================= -->
<div id="screen-code" class="screen">
  <div class="auth-bg">
    <div class="brand">
      <svg class="logo" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#ffffff"/><path d="M50 36 L63.3 45.7 L58.2 61.3 L41.8 61.3 L36.7 45.7 Z" fill="#1e6f5c"/></svg>
      <h1>Noteaza codul</h1>
    </div>
    <div class="auth-card">
      <h2 id="codeTitle">Contul a fost creat</h2>
      <p class="sub">Acesta este codul tau de recuperare. Este singura cale de a-ti schimba parola
        daca o uiti, iar aplicatia nu ti-l mai arata a doua oara. Fa o poza sau scrie-l undeva.</p>
      <div class="code-box">
        <span class="sub">Cod de recuperare</span>
        <b id="codeValue"></b>
      </div>
      <button class="btn" onclick="copyCode()">Copiaza codul</button>
      <button class="link" onclick="show('login')">L-am notat, mergi la autentificare</button>
    </div>
  </div>
</div>

<!-- ================= INREGISTRARE ================= -->
<div id="screen-register" class="screen">
  <div class="auth-bg">
    <button class="theme-btn auth-theme" onclick="toggleTheme()" title="Schimba tema"></button>
    <div class="brand">
      <svg class="logo" viewBox="0 0 100 100" aria-hidden="true"><circle cx="50" cy="50" r="46" fill="#ffffff"/><path d="M50 36 L63.3 45.7 L58.2 61.3 L41.8 61.3 L36.7 45.7 Z" fill="#1e6f5c"/><path d="M50 36 L50 6 M63.3 45.7 L88 27 M58.2 61.3 L74 84 M41.8 61.3 L26 84 M36.7 45.7 L12 27" stroke="#1e6f5c" stroke-width="3" fill="none" stroke-linecap="round"/></svg>
      <h1>Cont nou</h1>
    </div>
    <div class="auth-card" id="card-register">
      <h2>Creeaza cont</h2>
      <p class="sub">Dupa crearea contului te vei autentifica cu utilizatorul sau emailul si parola.</p>
      <label>Poza de profil (optional)</label>
      <div class="pick">
        <div class="ava" id="regAva"></div>
        <div class="pick-btns">
          <button type="button" onclick="document.getElementById('regAvaFile').click()">Alege din galerie</button>
          <button type="button" onclick="clearRegAvatar()">Scoate poza</button>
        </div>
      </div>
      <input id="regAvaFile" type="file" accept="image/*" style="display:none" onchange="pickRegAvatar(this)">
      <label>Utilizator</label>
      <input id="regUser" placeholder="Ex: ana.popescu">
      <label>Email</label>
      <input id="regEmail" type="email" placeholder="ana@exemplu.com">
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
    <div class="head-right">
      <button class="out bell" id="bell" onclick="openNotifications()" title="Notificari">
        <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M18 15V10a6 6 0 1 0-12 0v5l-1.5 2.5h15z"/><path d="M10 19a2 2 0 0 0 4 0"/></svg>
        <span class="dot" id="bellDot">0</span>
      </button>
      <button class="out theme-btn" onclick="toggleTheme()" title="Schimba tema"></button>
      <button class="out" onclick="logout()">Iesi</button>
    </div>
  </header>

  <main>
    <!-- ---------- feed de anunturi ---------- -->
    <section class="section active" id="sec-feed">
      <div class="filters" id="filters"></div>
      <div id="feed"></div>
      <div class="loader" id="feedLoader"></div>
    </section>

    <!-- ---------- anunt nou ---------- -->
    <section class="section" id="sec-new">
      <div class="card">
        <h2>Anunt nou de meci</h2>
        <p class="hint">Scrie ce sport joci, unde si cand. Ceilalti se pot inscrie.</p>
        <label>Sport</label>
        <input id="postSport" placeholder="Ex: fotbal">
        <label>Titlu</label>
        <input id="postTitle" placeholder="Ex: Meci de fotbal 5 la 5, miercuri seara">
        <label>Loc</label>
        <input id="postPlace" placeholder="Ex: Baza sportiva Fabric">
        <label>Cand</label>
        <input id="postWhen" placeholder="Ex: Miercuri, 19:00">
        <label>Locuri libere</label>
        <input id="postSlots" type="number" min="0" max="50" value="2">
        <label>Detalii</label>
        <textarea id="postBody" maxlength="600" placeholder="Nivel, cost teren, ce aduci cu tine..."></textarea>
        <button class="btn" onclick="publishPost()">Publica anuntul</button>
        <button class="link" onclick="go('feed', navBtn(0))">Renunta</button>
      </div>
    </section>

    <!-- ---------- harta terenului si traseul pana la el ---------- -->
    <section class="section" id="sec-map">
      <div class="card">
        <h2 id="mapTitle">Harta terenului</h2>
        <p class="hint" id="mapWhen"></p>
        <div class="modes">
          <button class="mode on" id="modeCar" onclick="setMode('masina')">Cu masina</button>
          <button class="mode" id="modeFoot" onclick="setMode('jos')">Pe jos</button>
        </div>
        <div class="nav-live" id="navLive">
          <div class="nav-step">
            <div class="nav-arrow" id="navArrow">^</div>
            <div class="nav-body">
              <div class="nav-dist" id="navDist">--</div>
              <div class="nav-text" id="navText">Pornim...</div>
            </div>
          </div>
          <div class="nav-nums">
            <div><b id="navLeft">--</b><span>ramas</span></div>
            <div><b id="navEta">--</b><span>ajungi la</span></div>
            <div><b id="navSpeed">--</b><span>km/h</span></div>
          </div>
        </div>
        <div class="mapbox" id="mapBox"></div>
        <div id="mapTrip"></div>
        <div id="navTools" class="nav-live">
          <div class="row">
            <button class="btn" onclick="stopNav()">Opreste navigarea</button>
            <button class="btn ghost" onclick="recenterNav()">Centreaza</button>
          </div>
          <div class="nav-apps">
            <a class="map-btn" id="navWaze" target="_blank" rel="noopener">Deschide in Waze</a>
            <a class="map-btn" id="navGmaps" target="_blank" rel="noopener">Google Maps</a>
          </div>
        </div>
        <div id="mapForm">
          <label>De unde pleci</label>
          <input id="mapFrom" placeholder="Ex: Strada Lipovei 20, Timisoara">
          <div class="row">
            <button class="btn" onclick="routeFromText()">Vezi traseul</button>
            <button class="btn ghost" onclick="routeFromGps()">Locatia mea</button>
          </div>
          <button class="btn accent" onclick="startNav()">Navigheaza spre teren</button>
          <button class="link" onclick="saveStart()">Salveaza plecarea ca implicita</button>
          <button class="link" onclick="go('feed', navBtn(0))">Inapoi la anunturi</button>
        </div>
      </div>
    </section>

    <!-- ---------- profil ---------- -->
    <section class="section" id="sec-profile">
      <div class="card">
        <div class="profile-top">
          <div class="ava" id="profAva"></div>
          <div class="profile-id">
            <h2 id="profName"></h2>
            <p class="where" id="profWhere"></p>
          </div>
        </div>
        <div class="stats" id="profStats"></div>
        <p class="bio" id="profBio"></p>
        <div class="meta" id="profSports"></div>
        <div class="row" id="profActions"></div>

        <div class="editor" id="profEditor">
          <label>Poza de profil</label>
          <div class="pick">
            <div class="ava" id="editAva"></div>
            <div class="pick-btns">
              <button onclick="document.getElementById('avaFile').click()">Alege poza</button>
              <button onclick="clearAvatar()">Scoate poza</button>
            </div>
          </div>
          <input id="avaFile" type="file" accept="image/*" style="display:none" onchange="pickAvatar(this)">
          <label>Descrierea profilului</label>
          <textarea id="editBio" maxlength="400" placeholder="Ce sporturi joci, ce nivel ai, ce cauti..."></textarea>
          <div class="count" id="bioCount"></div>
          <label>Zona / cartier</label>
          <input id="editZone">
          <label>Disponibilitate</label>
          <input id="editAvail">
          <div class="row">
            <button class="btn" onclick="saveProfile()">Salveaza</button>
            <button class="btn ghost" onclick="toggleEditor(false)">Anuleaza</button>
          </div>
        </div>
      </div>

      <div class="card" id="mySportsCard">
        <h2>Sporturile mele</h2>
        <p class="hint">Fiecare sport incepe de la nivelul Incepator.</p>
        <label>Adauga sport</label>
        <input id="newSport" placeholder="Ex: volei">
        <button class="btn" onclick="addSport()">Adauga</button>
      </div>

      <div class="card">
        <h2 id="profPostsTitle">Anunturile mele</h2>
        <div id="profPosts"></div>
      </div>

      <div class="card" id="communityCard">
        <h2>Comunitate</h2>
        <p class="hint">Apasa pe un nume ca sa vezi profilul.</p>
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

    <section class="section" id="sec-rank">
      <div class="card">
        <h2>Clasament</h2>
        <p class="hint">Top jucatori dupa puncte, pe fiecare sport. Poti filtra si pe zona.</p>
        <div class="filters" id="rankSports"></div>
        <label>Zona</label>
        <select id="rankZone" onchange="loadRanking()"></select>
      </div>
      <div class="card">
        <h2 id="rankTitle">Top jucatori</h2>
        <div id="rankList"></div>
      </div>
    </section>

    <section class="section" id="sec-chats">
      <div class="card">
        <h2>Conversatiile tale</h2>
        <p class="hint">Poti scrie doar jucatorilor cu care te-ai potrivit (v-ati apreciat reciproc).</p>
        <div id="chatList"></div>
      </div>
      <div class="card">
        <h2>Apreciaza un jucator</h2>
        <p class="hint">Potrivirea apare cand va apreciati amandoi.</p>
        <label>Apreciezi</label>
        <select id="likeTo"></select>
        <button class="btn" onclick="like()">Apreciaza</button>
      </div>
    </section>

    <section class="section" id="sec-chat">
      <div class="card">
        <div class="chat-head">
          <button class="back" onclick="go('chats', navBtn(3)); loadChats()">Inapoi</button>
          <div class="ava" id="chatAva"></div>
          <b id="chatName"></b>
        </div>
        <div class="thread" id="thread"></div>
        <div class="send-row">
          <input id="msgBody" maxlength="500" placeholder="Scrie un mesaj..."
                 onkeydown="if (event.key === 'Enter') sendMessage()">
          <button onclick="sendMessage()">Trimite</button>
        </div>
      </div>
    </section>

    <section class="section" id="sec-notif">
      <div class="card">
        <h2>Notificari</h2>
        <p class="hint">Cand cineva te apreciaza sau cand va potriviti, afli aici.</p>
        <div id="notifList"></div>
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
        <div class="calc" id="calc"></div>
        <button class="btn" onclick="recordMatch()">Inregistreaza meciul</button>
        <div id="recordResult" class="result-box"></div>
      </div>
    </section>
  </main>

  <button class="fab show" id="fab" onclick="openNewPost()" title="Anunt nou">+</button>

  <nav>
    <button class="active" onclick="go('feed', this)">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h10M4 11h10M4 16h6"/><path d="M17 5h3v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2"/></svg>
      <span>Anunturi</span>
    </button>
    <button onclick="openRanking(this)">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 20v-6M12 20V5M19 20v-9"/><path d="M3 20h18"/></svg>
      <span>Top</span>
    </button>
    <button onclick="go('search', this)">
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5"/><path d="M16 16l4 4"/></svg>
      <span>Cauta</span>
    </button>
    <button onclick="openChats(this)">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 12.5c0 3.6-3.6 6.5-8 6.5a9.6 9.6 0 0 1-2.6-.35L5 20.5l1.2-3.2A6.3 6.3 0 0 1 4 12.5C4 8.9 7.6 6 12 6s8 2.9 8 6.5z"/></svg>
      <span>Chat</span>
      <span class="dot"></span>
    </button>
    <button onclick="openRecord(this)">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4h10v5a5 5 0 0 1-10 0z"/><path d="M7 5H4v2a3 3 0 0 0 3 3M17 5h3v2a3 3 0 0 1-3 3"/><path d="M12 14v3M9 20h6"/></svg>
      <span>Meci</span>
    </button>
    <button onclick="openProfile(null, this)">
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8.5" r="3.5"/><path d="M5 19.5a7 7 0 0 1 14 0"/></svg>
      <span>Profil</span>
    </button>
  </nav>
</div>

<div id="msg" class="toast"></div>

        """;

    private static final String PAGE_JS = """
<script>
let token = localStorage.getItem('sm_token') || '';
let me = null;
let players = [];
let toastTimer = null;
let feedOffset = 0;
let feedMore = true;
let feedLoading = false;
let feedSport = '';
let viewingId = null;
let draftAvatar = null;
let regAvatar = '';
let rankSport = '';
let chatWith = null;
const SPORT_FILTERS = ['toate', 'fotbal', 'baschet', 'tenis', 'volei', 'padel', 'alergare', 'badminton'];

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

/* ---------- tema deschisa / intunecata ---------- */
const ICON_MOON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 14.5A8 8 0 0 1 9.5 4a8.2 8.2 0 1 0 10.5 10.5z"/></svg>';
const ICON_SUN = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.2"/><path d="M12 2.5v2M12 19.5v2M4.2 4.2l1.5 1.5M18.3 18.3l1.5 1.5M2.5 12h2M19.5 12h2M4.2 19.8l1.5-1.5M18.3 5.7l1.5-1.5"/></svg>';

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  try { localStorage.setItem('sm_theme', theme); } catch (e) { /* ignoram */ }
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', theme === 'dark' ? '#101e1a' : '#1e6f5c');
  document.querySelectorAll('.theme-btn').forEach(b => {
    b.innerHTML = theme === 'dark' ? ICON_SUN : ICON_MOON;
    b.setAttribute('aria-label', theme === 'dark' ? 'Treci pe tema deschisa' : 'Treci pe tema intunecata');
  });
}
function toggleTheme() {
  const dark = document.documentElement.getAttribute('data-theme') === 'dark';
  applyTheme(dark ? 'light' : 'dark');
  showMsg(dark ? 'Tema deschisa' : 'Tema intunecata');
}
function startTheme() {
  let saved = null;
  try { saved = localStorage.getItem('sm_theme'); } catch (e) { /* ignoram */ }
  if (!saved) {
    saved = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  applyTheme(saved);
}

function show(name) {
  document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
  document.getElementById('screen-' + name).classList.add('active');
}
function navBtn(i) {
  return document.querySelectorAll('nav button')[i];
}
function go(name, btn) {
  if (name !== 'map' && navOn) stopNav();
  document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
  document.getElementById('sec-' + name).classList.add('active');
  document.querySelectorAll('nav button').forEach(b => b.classList.remove('active'));
  if (btn) btn.classList.add('active');
  document.getElementById('fab').classList.toggle('show', name === 'feed');
  window.scrollTo(0, 0);
}

/* ---------- avatare ---------- */
function initials(name) {
  const s = String(name || '?');
  let out = s.charAt(0);
  for (let i = 1; i < s.length && out.length < 2; i++) {
    const prev = s.charAt(i - 1);
    if (prev === '.' || prev === ' ' || prev === '_' || prev === '-') out += s.charAt(i);
  }
  return out;
}
function avaColor(name) {
  let h = 0;
  const s = String(name || '');
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
  return 'linear-gradient(135deg, hsl(' + h + ',46%,44%), hsl(' + ((h + 40) % 360) + ',52%,30%))';
}
function avaHtml(name, avatar) {
  const inner = avatar ? '<img src="' + esc(avatar) + '" alt="">' : esc(initials(name));
  return '<div class="ava" style="background:' + avaColor(name) + '">' + inner + '</div>';
}
function fillAva(el, name, avatar) {
  el.style.background = avaColor(name);
  el.innerHTML = avatar ? '<img src="' + esc(avatar) + '" alt="">' : esc(initials(name));
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
      username: v('regUser'), email: v('regEmail'),
      password: document.getElementById('regPwd').value,
      sports: v('regSports'), availability: v('regAvail'), zone: v('regZone'),
      avatar: regAvatar
    });
    document.getElementById('regPwd').value = '';
    regAvatar = '';
    renderRegAvatar();
    document.getElementById('loginId').value = v('regUser');
    showCode(j.code, 'Contul a fost creat');
  } catch (e) { fail(e); }
}

async function login() {
  try {
    const j = await api('login', 'POST', {
      identifier: v('loginId'), password: document.getElementById('loginPwd').value
    });
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
  go('feed', navBtn(0));
  renderFilters();
  resetFeed();
  loadPlayers();
  refreshBadges();
}

/* verificam periodic mesajele si notificarile */
setInterval(() => {
  if (!token || !me) return;
  if (document.getElementById('sec-chat').classList.contains('active')) loadThread(false);
  else refreshBadges();
}, 10000);

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
      list.innerHTML += '<div class="item"><div class="post-head">' + avaHtml(p.name, p.avatar) +
        '<div class="post-who"><b onclick="openProfile(' + p.id + ')">' + esc(p.name) + esc(mine) + '</b>' +
        '<span>' + esc(p.zone) + ' · ' + esc(p.availability) + '</span></div></div>' +
        '<div class="meta">' + chips + '</div></div>';
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
    loadChats();
    refreshBadges();
  } catch (e) { fail(e); }
}

async function recordMatch() {
  try {
    const j = await api('match', 'POST', {
      sport: v('recordSport'), won: v('result'),
      teammates: v('teammates'), opponents: v('opponents')
    });
    document.getElementById('recordResult').textContent = j.message;
    ['teammates', 'opponents'].forEach(id => document.getElementById(id).value = '');
    loadPlayers();
    loadCalc();
  } catch (e) { fail(e); }
}

/* ================= FEED DE ANUNTURI ================= */

function renderFilters() {
  const box = document.getElementById('filters');
  box.innerHTML = SPORT_FILTERS.map((s, i) =>
    '<button class="' + ((feedSport === '' && i === 0) || feedSport === s ? 'on' : '') + '" ' +
    'onclick="setFilter(' + (i === 0 ? "''" : "'" + s + "'") + ')">' + esc(s) + '</button>').join('');
}
function setFilter(sport) {
  feedSport = sport;
  renderFilters();
  resetFeed();
  window.scrollTo(0, 0);
}
function resetFeed() {
  feedOffset = 0;
  feedMore = true;
  document.getElementById('feed').innerHTML = '';
  loadFeed();
}

async function loadFeed() {
  if (feedLoading || !feedMore) return;
  feedLoading = true;
  const loader = document.getElementById('feedLoader');
  loader.textContent = 'Se incarca anunturile...';
  try {
    const j = await api('feed?offset=' + feedOffset + '&limit=8' +
      (feedSport ? '&sport=' + encodeURIComponent(feedSport) : ''));
    const box = document.getElementById('feed');
    j.items.forEach(post => {
      const d = document.createElement('div');
      d.innerHTML = postHtml(post);
      box.appendChild(d.firstChild);
    });
    feedOffset += j.items.length;
    feedMore = j.more;
    if (!feedOffset) {
      loader.textContent = '';
      box.innerHTML = '<div class="card"><div class="empty">Nu exista anunturi pentru acest sport. ' +
        'Publica tu primul!</div></div>';
    } else {
      loader.textContent = feedMore ? 'Deruleaza pentru mai multe...' : 'Ai ajuns la capatul listei.';
    }
  } catch (e) {
    loader.textContent = '';
    fail(e);
  } finally {
    feedLoading = false;
  }
}

function postHtml(p) {
  const slots = p.slots > 0 ? p.slots + ' locuri libere' : 'fara locuri libere';
  let btn;
  if (p.isMine) {
    btn = '<button class="join own">Anuntul tau</button>';
  } else {
    btn = '<button class="join' + (p.joinedByMe ? '' : ' off') + '" onclick="joinPost(' + p.id + ', this)">' +
      (p.joinedByMe ? 'Inscris' : 'Ma inscriu') + '</button>';
  }
  return '<div class="post" id="post-' + p.id + '">' +
    '<div class="post-head">' + avaHtml(p.author, p.avatar) +
      '<div class="post-who"><b onclick="openProfile(' + p.authorId + ')">' + esc(p.author) + '</b>' +
      '<span>' + esc(p.createdAt) + '</span></div>' +
      '<span class="sport-tag">' + esc(p.sport) + '</span></div>' +
    '<h3>' + esc(p.title) + '</h3>' +
    (p.body ? '<p>' + esc(p.body) + '</p>' : '') +
    '<div class="post-meta"><span>' + esc(p.when) + '</span><span>' + esc(p.place) + '</span>' +
      '<span>' + esc(slots) + '</span></div>' +
    '<div class="post-foot"><span class="who" id="joined-' + p.id + '">' +
      joinedText(p.joined) + '</span>' +
      '<button class="map-btn" onclick="openMap(' + p.id + ')">Harta</button>' +
      btn + '</div></div>';
}
function joinedText(n) {
  if (!n) return 'Nimeni inscris inca';
  return n === 1 ? '1 persoana inscrisa' : n + ' persoane inscrise';
}

async function joinPost(id, btn) {
  try {
    const j = await api('join', 'POST', { post: id });
    const on = btn.classList.contains('off');
    btn.classList.toggle('off', !on);
    btn.textContent = on ? 'Inscris' : 'Ma inscriu';
    showMsg(j.message);
    const label = document.getElementById('joined-' + id);
    if (label) {
      const n = Math.max(0, (parseInt(label.textContent, 10) || 0) + (on ? 1 : -1));
      label.textContent = joinedText(n);
    }
  } catch (e) { fail(e); }
}


/* ---------- harta terenului, plecarea si traseul ----------
   Datele vin de la serverul nostru, care la rand intreaba
   Nominatim (adresa <-> coordonate) si OSRM (traseul). */
let mapView = null;
let mapLayers = [];
let mapPost = 0;
let mapMode = 'masina';

function openMap(id) {
  mapPost = id;
  go('map', null);
  document.getElementById('mapTitle').textContent = 'Harta terenului';
  document.getElementById('mapWhen').textContent = '';
  document.getElementById('mapTrip').innerHTML = '<div class="trip-line">Se incarca harta...</div>';
  loadMap('');
}

async function loadMap(extra) {
  try {
    const j = await api('map?post=' + mapPost + '&mode=' + mapMode + (extra || ''));
    document.getElementById('mapTitle').textContent = j.title;
    document.getElementById('mapWhen').textContent = j.when + ' · ' + j.place;
    if (j.from) document.getElementById('mapFrom').value = j.from.addr;
    drawMap(j);
    let html = '<div class="trip-line"><b>Teren:</b> ' + esc(j.dest.addr) + '</div>';
    if (j.from && j.trip) {
      html = '<div class="trip-nums"><div><b>' + Number(j.trip.km).toFixed(1) + ' km</b>' +
        '<span>distanta</span></div>' +
        (j.trip.minutes ? '<div><b>' + j.trip.minutes + ' min</b><span>' +
          (mapMode === 'jos' ? 'pe jos' : 'cu masina') + '</span></div>' : '') +
        '</div>' + html +
        '<div class="trip-line"><b>Plecare:</b> ' + esc(j.from.addr) + '</div>';
    } else {
      html += '<div class="trip-line">Scrie de unde pleci si vezi traseul si cat faci pana la teren.</div>';
    }
    if (j.note) html += '<div class="trip-warn">' + esc(j.note) + '</div>';
    document.getElementById('mapTrip').innerHTML = html;
  } catch (e) {
    document.getElementById('mapTrip').innerHTML = '';
    fail(e);
  }
}

function drawMap(j) {
  const box = document.getElementById('mapBox');
  if (typeof L === 'undefined') {
    box.innerHTML = '<div class="empty">Harta are nevoie de internet ca sa se incarce. ' +
      'Distanta si traseul se vad mai jos.</div>';
    return;
  }
  if (!mapView) {
    mapView = L.map('mapBox', { scrollWheelZoom: false });
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, attribution: 'Harta: OpenStreetMap'
    }).addTo(mapView);
    mapView.on('dragstart', () => { navFollow = false; });
  }
  mapLayers.forEach(layer => mapView.removeLayer(layer));
  mapLayers = [];
  const dest = [j.dest.lat, j.dest.lon];
  mapLayers.push(L.marker(dest).addTo(mapView).bindPopup('Teren: ' + j.place));
  if (j.from) {
    const from = [j.from.lat, j.from.lon];
    mapLayers.push(L.marker(from).addTo(mapView).bindPopup('Plecare: ' + j.from.addr));
    const line = ((j.trip && j.trip.coords) || []).map(c => [c[1], c[0]]);
    if (line.length > 1) {
      mapLayers.push(L.polyline(line, { color: '#1e6f5c', weight: 5, opacity: 0.85 }).addTo(mapView));
      mapView.fitBounds(L.latLngBounds(line).pad(0.15));
    } else {
      mapView.fitBounds(L.latLngBounds([from, dest]).pad(0.3));
    }
  } else {
    mapView.setView(dest, 15);
  }
  setTimeout(() => mapView.invalidateSize(), 120);
}


/* ---------- navigare in timp real, ca in aplicatiile de drum ----------
   Telefonul da pozitia (watchPosition), noi o desenam pe harta si cerem
   de la server un traseu proaspat cu virajele pana la teren. */
let navWatch = null;
let navOn = false;
let navSteps = [];
let navIndex = 0;
let navDest = null;
let navPts = [];
let navCum = [];
let navTotalKm = 0;
let navTotalMin = 0;
let navRemainKm = 0;
let navRemainMin = 0;
let navLastFix = null;
let navLastAsk = 0;
let navFollow = true;
let navSpoken = {};
let navMe = null;
let navRing = null;
let navLine = null;
let navWakeLock = null;

const NAV_ARROWS = {
  left: '<', right: '>', 'slight left': '<', 'slight right': '>',
  'sharp left': '<', 'sharp right': '>', straight: '^', uturn: 'U'
};

function startNav() {
  if (!navigator.geolocation) {
    showMsg('Dispozitivul nu da acces la locatie.');
    return;
  }
  if (navOn) return;
  navOn = true;
  navSteps = [];
  navPts = [];
  navCum = [];
  navIndex = 0;
  navSpoken = {};
  navLastFix = null;
  navLastAsk = 0;
  navFollow = true;
  document.getElementById('navLive').classList.add('on');
  document.getElementById('navTools').classList.add('on');
  document.getElementById('mapForm').style.display = 'none';
  document.getElementById('mapTrip').style.display = 'none';
  document.getElementById('mapTitle').textContent = 'Navigare spre teren';
  setNavStep('^', '', 'Astept semnalul GPS...');
  keepAwake();
  navWatch = navigator.geolocation.watchPosition(onNavFix, onNavError,
    { enableHighAccuracy: true, maximumAge: 2000, timeout: 20000 });
}

function stopNav() {
  navOn = false;
  if (navWatch !== null) navigator.geolocation.clearWatch(navWatch);
  navWatch = null;
  navSteps = [];
  if (navWakeLock) { navWakeLock.release().catch(() => {}); navWakeLock = null; }
  if (window.speechSynthesis) speechSynthesis.cancel();
  const live = document.getElementById('navLive');
  if (live) {
    live.classList.remove('on');
    document.getElementById('navTools').classList.remove('on');
    document.getElementById('mapForm').style.display = '';
    document.getElementById('mapTrip').style.display = '';
  }
  if (mapView) {
    [navMe, navRing, navLine].forEach(layer => { if (layer) mapView.removeLayer(layer); });
  }
  navMe = navRing = navLine = null;
}

async function keepAwake() {
  try {
    if ('wakeLock' in navigator) navWakeLock = await navigator.wakeLock.request('screen');
  } catch (e) { /* merge si fara, doar ca se stinge ecranul */ }
}

function onNavError(err) {
  showMsg(err.code === 1
    ? 'Nu ai dat voie la locatie. Permite-o din setarile browserului.'
    : 'Nu primesc semnal GPS. Iesi in aer liber si incearca iar.');
}

async function onNavFix(pos) {
  if (!navOn) return;
  const here = { lat: pos.coords.latitude, lon: pos.coords.longitude };
  drawMe(here, pos.coords.accuracy);
  if (navFollow && mapView) mapView.setView([here.lat, here.lon], Math.max(mapView.getZoom(), 16));

  const speed = pos.coords.speed;
  document.getElementById('navSpeed').textContent =
    (speed === null || isNaN(speed)) ? '--' : Math.round(speed * 3.6);

  navLastFix = here;

  const now = Date.now();
  const old = now - navLastAsk > 25000;
  const away = navSteps.length > 0 && offRoute(here) > 0.08;
  if (!navSteps.length || old || away) {
    navLastAsk = now;
    await askRoute(here);
  }
  updateStep(here);
}

/* traseul proaspat de la server, cu virajele de pe drum */
async function askRoute(here) {
  try {
    const j = await api('nav?post=' + mapPost + '&mode=' + mapMode +
      '&lat=' + here.lat + '&lon=' + here.lon);
    navDest = j.dest;
    if (j.arrived) { arrived(); return; }
    navPts = (j.trip.coords || []).map(c => ({ lat: c[1], lon: c[0] }));
    navCum = [0];
    for (let i = 1; i < navPts.length; i++) {
      navCum[i] = navCum[i - 1] + kmBetween(navPts[i - 1], navPts[i]);
    }
    navTotalKm = Number(j.trip.km);
    navTotalMin = j.trip.minutes;
    navSteps = (j.trip.steps || []).map(st => Object.assign({}, st, { vi: nearestVertex(st) }));
    navIndex = navSteps.length > 1 ? 1 : 0;
    navRemainKm = navTotalKm;
    navRemainMin = navTotalMin;
    showRemaining();
    drawNavLine(j.trip.coords);
    setLinks();
  } catch (e) {
    document.getElementById('navText').textContent = 'Nu am putut lua traseul. Incerc din nou.';
  }
}

function drawMe(here, accuracy) {
  if (!mapView || typeof L === 'undefined') return;
  const point = [here.lat, here.lon];
  if (!navMe) {
    navMe = L.marker(point, {
      icon: L.divIcon({ className: '', html: '<div class="me-dot"></div>', iconSize: [18, 18] })
    }).addTo(mapView);
    navRing = L.circle(point, { radius: accuracy || 20, color: '#2176ff', weight: 1, opacity: 0.5,
      fillOpacity: 0.12 }).addTo(mapView);
  } else {
    navMe.setLatLng(point);
    navRing.setLatLng(point).setRadius(accuracy || 20);
  }
}

function drawNavLine(coords) {
  if (!mapView || typeof L === 'undefined') return;
  const line = (coords || []).map(c => [c[1], c[0]]);
  if (navLine) mapView.removeLayer(navLine);
  navLine = line.length > 1
    ? L.polyline(line, { color: '#2176ff', weight: 6, opacity: 0.8 }).addTo(mapView)
    : null;
}

/* cat de departe sunt de traseul desenat, in km */
function offRoute(here) {
  if (!navPts.length) return 0;
  let best = 999;
  navPts.forEach(pt => { best = Math.min(best, kmBetween(here, pt)); });
  return best;
}

/* punctul de pe traseu cel mai apropiat de o pozitie */
function nearestVertex(point) {
  let best = 0;
  let bestKm = 999;
  for (let i = 0; i < navPts.length; i++) {
    const d = kmBetween(point, navPts[i]);
    if (d < bestKm) { bestKm = d; best = i; }
  }
  return best;
}

/* virajul care urmeaza si cati metri mai sunt pana la el */
function updateStep(here) {
  if (!navSteps.length || !navPts.length) return;
  const vi = nearestVertex(here);

  /* urmatorul viraj e primul care vine dupa punctul in care sunt acum */
  let next = navSteps.findIndex(st => st.vi > vi);
  if (next < 0) next = navSteps.length - 1;
  navIndex = next;
  const step = navSteps[next];
  const meters = kmBetween(here, step) * 1000;

  /* cat mai am de mers: restul traseului de la punctul curent incolo */
  const total = navCum[navCum.length - 1] || navTotalKm;
  navRemainKm = Math.max(0, total - (navCum[vi] || 0));
  navRemainMin = navTotalKm > 0
    ? Math.max(navRemainKm > 0.05 ? 1 : 0, Math.round(navTotalMin * navRemainKm / navTotalKm))
    : 0;
  showRemaining();

  if (next === navSteps.length - 1 && meters < 60) { arrived(); return; }
  setNavStep(NAV_ARROWS[step.modifier] || '^', metersText(meters), step.text);
  speakStep(next, meters, step.spoken || step.text);
}

function setNavStep(arrow, distance, text) {
  document.getElementById('navArrow').textContent = arrow;
  document.getElementById('navDist').textContent = distance;
  document.getElementById('navText').textContent = text;
}

function metersText(meters) {
  if (meters >= 1000) return (meters / 1000).toFixed(1) + ' km';
  return Math.round(meters / 10) * 10 + ' m';
}

function showRemaining() {
  document.getElementById('navLeft').textContent = navRemainKm.toFixed(1) + ' km';
  const eta = new Date(Date.now() + navRemainMin * 60000);
  document.getElementById('navEta').textContent =
    String(eta.getHours()).padStart(2, '0') + ':' + String(eta.getMinutes()).padStart(2, '0');
}

/* indicatia se spune cu voce tare o data de departe si o data aproape */
function speakStep(index, meters, text) {
  if (!window.speechSynthesis) return;
  const far = index + ':departe';
  const near = index + ':aproape';
  if (meters < 350 && meters > 120 && !navSpoken[far]) {
    navSpoken[far] = true;
    say('Peste ' + metersText(meters) + ', ' + text);
  } else if (meters <= 120 && !navSpoken[near]) {
    navSpoken[near] = true;
    say(text);
  }
}

function say(text) {
  try {
    const voice = new SpeechSynthesisUtterance(text);
    voice.lang = 'ro-RO';
    speechSynthesis.speak(voice);
  } catch (e) { /* unele browsere nu au voce */ }
}

function arrived() {
  setNavStep('*', '', 'Ai ajuns la teren. Joc frumos!');
  document.getElementById('navLeft').textContent = '0 km';
  document.getElementById('navEta').textContent = 'acum';
  say('Ai ajuns la teren.');
  navSteps = [];
}

function recenterNav() {
  navFollow = true;
  if (mapView && navLastFix) mapView.setView([navLastFix.lat, navLastFix.lon], 16);
}

function setLinks() {
  if (!navDest) return;
  const point = navDest.lat + ',' + navDest.lon;
  document.getElementById('navWaze').href = 'https://waze.com/ul?ll=' + point + '&navigate=yes';
  document.getElementById('navGmaps').href =
    'https://www.google.com/maps/dir/?api=1&destination=' + point + '&travelmode=driving';
}

/* distanta in linie dreapta intre doua puncte, in km */
function kmBetween(a, b) {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLon = (b.lon - a.lon) * rad;
  const h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
}

/* cu masina sau pe jos: schimbam serviciul care calculeaza drumul */
function setMode(mode) {
  if (mapMode === mode) return;
  mapMode = mode;
  document.getElementById('modeCar').classList.toggle('on', mode === 'masina');
  document.getElementById('modeFoot').classList.toggle('on', mode === 'jos');
  navSpoken = {};
  if (navOn) {
    navSteps = [];
    navPts = [];
    navLastAsk = Date.now();
    setNavStep('^', '', 'Recalculez traseul...');
    if (navLastFix) askRoute(navLastFix).then(() => { if (navLastFix) updateStep(navLastFix); });
    return;
  }
  const from = v('mapFrom');
  document.getElementById('mapTrip').innerHTML = '<div class="trip-line">Recalculez traseul...</div>';
  loadMap(from ? '&from=' + encodeURIComponent(from) : '');
}

function routeFromText() {
  const from = v('mapFrom');
  if (!from) { showMsg('Scrie de unde pleci.'); return; }
  document.getElementById('mapTrip').innerHTML = '<div class="trip-line">Caut traseul...</div>';
  loadMap('&from=' + encodeURIComponent(from));
}

function routeFromGps() {
  if (!navigator.geolocation) {
    showMsg('Dispozitivul nu da acces la locatie. Scrie adresa de plecare.');
    return;
  }
  document.getElementById('mapTrip').innerHTML = '<div class="trip-line">Iti caut locatia...</div>';
  navigator.geolocation.getCurrentPosition(
    pos => loadMap('&fromLat=' + pos.coords.latitude + '&fromLon=' + pos.coords.longitude),
    () => {
      document.getElementById('mapTrip').innerHTML = '';
      showMsg('Nu am primit locatia. Scrie adresa de plecare.');
    },
    { timeout: 8000, enableHighAccuracy: false });
}

async function saveStart() {
  const from = v('mapFrom');
  if (!from) { showMsg('Scrie mai intai de unde pleci.'); return; }
  try {
    const j = await api('start', 'POST', { from: from });
    document.getElementById('mapFrom').value = j.from.addr;
    showMsg(j.message);
  } catch (e) { fail(e); }
}

function openNewPost() {
  go('new', null);
  document.getElementById('postSport').focus();
}

async function publishPost() {
  try {
    const j = await api('post', 'POST', {
      sport: v('postSport'), title: v('postTitle'), place: v('postPlace'),
      when: v('postWhen'), slots: v('postSlots'), body: v('postBody')
    });
    ['postSport', 'postTitle', 'postPlace', 'postWhen', 'postBody'].forEach(
      id => document.getElementById(id).value = '');
    document.getElementById('postSlots').value = '2';
    showMsg(j.message);
    feedSport = '';
    renderFilters();
    go('feed', navBtn(0));
    resetFeed();
    loadPlayers();
  } catch (e) { fail(e); }
}

/* incarcare la derulare (scroll infinit), doar cand esti in aplicatie */
window.addEventListener('scroll', () => {
  if (!token || !document.getElementById('screen-app').classList.contains('active')) return;
  if (!document.getElementById('sec-feed').classList.contains('active')) return;
  const left = document.body.offsetHeight - (window.scrollY + window.innerHeight);
  if (left < 320) loadFeed();
});

/* ================= PAGINA DE PROFIL ================= */

async function openProfile(id, btn) {
  viewingId = id || null;
  go('profile', btn || navBtn(5));
  await loadProfile();
}

async function loadProfile() {
  try {
    const j = await api('profile' + (viewingId ? '?id=' + viewingId : ''));
    const p = j.player;
    fillAva(document.getElementById('profAva'), p.name, p.avatar);
    document.getElementById('profName').textContent = p.name + (j.mine ? ' (tu)' : '');
    document.getElementById('profWhere').textContent =
      [p.zone || 'zona nesetata', p.availability || 'disponibilitate nesetata'].join(' · ');

    const s = j.stats;
    document.getElementById('profStats').innerHTML =
      '<div><b>' + s.posts + '</b><span>anunturi</span></div>' +
      '<div><b>' + s.sports + '</b><span>sporturi</span></div>' +
      '<div><b>' + s.played + '</b><span>meciuri</span></div>' +
      '<div><b>' + s.wins + '</b><span>victorii</span></div>';

    const bio = document.getElementById('profBio');
    bio.textContent = p.bio || (j.mine
      ? 'Nu ai scris nicio descriere. Apasa Editeaza profilul.'
      : 'Fara descriere.');
    bio.classList.toggle('none', !p.bio);

    document.getElementById('profSports').innerHTML = p.sports.map(x =>
      '<span class="chip">' + esc(x.sport) + ' · ' + esc(x.level) + ' (' + x.points + ')</span>').join('');
    document.getElementById('profActions').innerHTML = j.mine
      ? '<button class="btn" onclick="toggleEditor(true)">Editeaza profilul</button>' +
        '<button class="btn ghost" onclick="openNewPost()">Anunt nou</button>'
      : '<button class="btn ghost" onclick="openProfile(null)">Inapoi la profilul meu</button>';

    document.getElementById('mySportsCard').style.display = j.mine ? '' : 'none';
    document.getElementById('communityCard').style.display = j.mine ? '' : 'none';
    document.getElementById('profPostsTitle').textContent = j.mine
      ? 'Anunturile mele' : 'Anunturile lui ' + p.name;

    const box = document.getElementById('profPosts');
    box.innerHTML = j.posts.length ? '' : '<div class="empty">Nu exista anunturi.</div>';
    j.posts.forEach(post => { box.innerHTML += postHtml(post); });

    document.getElementById('editBio').value = p.bio || '';
    document.getElementById('editZone').value = p.zone || '';
    document.getElementById('editAvail').value = p.availability || '';
    draftAvatar = p.avatar || '';
    renderEditAvatar(p.name);
    toggleEditor(false);
    updateBioCount();
  } catch (e) { fail(e); }
}

function renderEditAvatar(name) {
  const el = document.getElementById('editAva');
  fillAva(el, name || (me ? me.username : '?'), draftAvatar);
}
function toggleEditor(open) {
  document.getElementById('profEditor').classList.toggle('open', !!open);
  if (open) document.getElementById('editBio').focus();
}
function updateBioCount() {
  const t = document.getElementById('editBio');
  document.getElementById('bioCount').textContent = t.value.length + ' / 400';
}
function clearAvatar() {
  draftAvatar = '';
  renderEditAvatar();
}

/* poza aleasa din galerie este decupata patrat si micsorata la 320x320 in browser */
function readAvatar(input, done) {
  const file = input.files && input.files[0];
  input.value = '';
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    const img = new Image();
    img.onload = () => {
      const size = 320;
      const canvas = document.createElement('canvas');
      canvas.width = size;
      canvas.height = size;
      const side = Math.min(img.width, img.height);
      canvas.getContext('2d').drawImage(img,
        (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, size, size);
      done(canvas.toDataURL('image/jpeg', 0.85));
    };
    img.onerror = () => showMsg('Fisierul nu este o imagine valida.');
    img.src = reader.result;
  };
  reader.onerror = () => showMsg('Nu am putut citi fisierul.');
  reader.readAsDataURL(file);
}
function pickAvatar(input) {
  readAvatar(input, data => { draftAvatar = data; renderEditAvatar(); });
}
function pickRegAvatar(input) {
  readAvatar(input, data => { regAvatar = data; renderRegAvatar(); });
}
function clearRegAvatar() {
  regAvatar = '';
  renderRegAvatar();
}
function renderRegAvatar() {
  fillAva(document.getElementById('regAva'), v('regUser') || '?', regAvatar);
}

async function saveProfile() {
  try {
    const j = await api('profile', 'POST', {
      bio: document.getElementById('editBio').value,
      avatar: draftAvatar === null ? '' : draftAvatar,
      zone: v('editZone'), availability: v('editAvail')
    });
    showMsg(j.message);
    me = await api('me');
    await loadProfile();
    loadPlayers();
    resetFeed();
  } catch (e) { fail(e); }
}

/* ================= CAT VALOREAZA MECIUL ================= */

let calcTimer = null;
function openRecord(btn) {
  go('record', btn || navBtn(4));
  loadCalc();
}
function calcLater() {
  clearTimeout(calcTimer);
  calcTimer = setTimeout(loadCalc, 450);
}

async function loadCalc() {
  const box = document.getElementById('calc');
  const sport = v('recordSport');
  if (!sport) {
    box.innerHTML = '<p class="hint">Scrie sportul si numele jucatorilor ca sa vezi cat valoreaza meciul.</p>';
    return;
  }
  try {
    const j = await api('preview?sport=' + encodeURIComponent(sport) +
      '&teammates=' + encodeURIComponent(v('teammates')) +
      '&opponents=' + encodeURIComponent(v('opponents')));

    const side = (title, list, extra) => {
      if (!list.length) return '';
      return '<div class="calc-side"><h3>' + title + '</h3>' + list.map(pl =>
        '<div class="calc-line">' + avaHtml(pl.name, pl.avatar) +
        '<span class="who">' + esc(pl.name) + (pl.self ? ' (tu)' : '') + '</span>' +
        '<span class="lvl">' + esc(pl.level) + ' · ' + pl.points + ' p</span></div>').join('') +
        (extra || '') + '</div>';
    };

    const mine = Object.assign({ self: true }, j.me);
    box.innerHTML =
      '<div class="calc-outcomes">' +
        '<div class="won"><b>+' + j.win + '</b><span>daca castigi</span></div>' +
        '<div class="lost"><b>' + j.loss + '</b><span>daca pierzi</span></div>' +
      '</div>' +
      '<div class="calc-avg"><span>Total acum: ' + j.ownPoints + ' p</span>' +
        '<span>Victorie: ' + j.afterWin + ' p · Infrangere: ' + j.afterLoss + ' p</span></div>' +
      side('Echipa ta', [mine].concat(j.team)) +
      side('Adversari', j.opponents) +
      '<div class="calc-avg"><span>Media coechipierilor: ' + levelName(j.teamAvg) + '</span>' +
        '<span>Media adversarilor: ' + levelName(j.oppAvg) + '</span></div>' +
      (j.unknown.length
        ? '<p class="calc-warn">Nu am gasit la acest sport: ' + esc(j.unknown.join(', ')) +
          '. Verifica numele, altfel nu intra in calcul.</p>'
        : '') +
      (j.opponents.length ? '' : '<p class="hint">Adauga adversari ca sa vezi calculul real.</p>');
  } catch (e) {
    box.innerHTML = '<p class="calc-warn">' + esc(e.message) + '</p>';
  }
}

/* media de nivel, ca text: 0 = Incepator, 1 = Intermediar, 2 = Avansat */
function levelName(avg) {
  const names = ['Incepator', 'Intermediar', 'Avansat'];
  const n = Number(avg);
  return names[Math.round(n)] + ' (' + n.toFixed(1) + ')';
}

/* ================= CLASAMENT ================= */

async function openRanking(btn) {
  go('rank', btn || navBtn(1));
  await loadRanking();
}

async function loadRanking() {
  try {
    const zone = document.getElementById('rankZone').value || '';
    const j = await api('ranking?sport=' + encodeURIComponent(rankSport) +
      '&zone=' + encodeURIComponent(zone));
    rankSport = j.sport;

    document.getElementById('rankSports').innerHTML = j.sports.length
      ? j.sports.map(s => '<button class="' + (s === j.sport ? 'on' : '') + '" ' +
          'onclick="setRankSport(' + "'" + esc(s) + "'" + ')">' + esc(s) + '</button>').join('')
      : '<span class="empty">Niciun sport inca.</span>';

    const sel = document.getElementById('rankZone');
    if (sel.options.length !== j.zones.length + 1) {
      sel.innerHTML = '<option value="">Toate zonele</option>' +
        j.zones.map(z => '<option value="' + esc(z) + '">' + esc(z) + '</option>').join('');
      sel.value = j.zone;
    }

    document.getElementById('rankTitle').textContent = 'Top ' + (j.sport || 'jucatori') +
      (j.zone ? ' · ' + j.zone : '');

    const box = document.getElementById('rankList');
    box.innerHTML = j.items.length ? '' : '<div class="empty">Nimeni nu are puncte aici inca.</div>';
    j.items.forEach(r => {
      const medal = r.rank <= 3 ? ' top' + r.rank : '';
      const mine = me && r.id === me.playerId ? ' me-row' : '';
      box.innerHTML += '<div class="rank-row' + medal + mine + '">' +
        '<span class="rank-no">' + r.rank + '</span>' + avaHtml(r.name, r.avatar) +
        '<div class="rank-who"><b onclick="openProfile(' + r.id + ')">' + esc(r.name) + '</b>' +
        '<span>' + esc(r.level) + ' · ' + esc(r.zone || 'fara zona') + ' · ' +
        r.played + ' meciuri</span></div>' +
        '<div class="rank-pts"><b>' + r.points + '</b><span>puncte</span></div></div>';
    });
  } catch (e) { fail(e); }
}
function setRankSport(sport) {
  rankSport = sport;
  loadRanking();
}

/* ================= CHAT ================= */

async function openChats(btn) {
  go('chats', btn || navBtn(3));
  await loadChats();
}

async function loadChats() {
  try {
    const j = await api('chats');
    const box = document.getElementById('chatList');
    box.innerHTML = j.items.length ? ''
      : '<div class="empty">Nu ai potriviri inca. Apreciaza jucatori si asteapta sa te aprecieze si ei.</div>';
    j.items.forEach(c => {
      const last = c.last ? esc(c.last) : 'Nicio discutie inca. Scrie primul mesaj.';
      box.innerHTML += '<div class="chat-row" onclick="openChat(' + c.id + ')">' +
        avaHtml(c.name, c.avatar) +
        '<div class="chat-who"><b>' + esc(c.name) + '</b><span>' + last + '</span></div>' +
        (c.unread ? '<span class="badge">' + c.unread + '</span>' : '') + '</div>';
    });
  } catch (e) { fail(e); }
}

async function openChat(id) {
  chatWith = id;
  go('chat', navBtn(3));
  await loadThread(true);
}

async function loadThread(scroll) {
  if (!chatWith) return;
  try {
    const j = await api('messages?with=' + chatWith);
    fillAva(document.getElementById('chatAva'), j.with.name, j.with.avatar);
    document.getElementById('chatName').textContent = j.with.name;
    const box = document.getElementById('thread');
    box.innerHTML = j.items.length ? ''
      : '<div class="empty">Niciun mesaj inca. Propune un meci!</div>';
    j.items.forEach(m => {
      box.innerHTML += '<div class="bubble ' + (m.mine ? 'me' : 'them') + '">' + esc(m.body) +
        '<small>' + esc(m.at) + '</small></div>';
    });
    if (scroll) box.scrollTop = box.scrollHeight;
    refreshBadges();
  } catch (e) { fail(e); }
}

async function sendMessage() {
  const input = document.getElementById('msgBody');
  const body = input.value.trim();
  if (!body) return;
  try {
    await api('message', 'POST', { to: chatWith, body: body });
    input.value = '';
    await loadThread(true);
  } catch (e) { fail(e); }
}

/* ================= NOTIFICARI ================= */

async function openNotifications() {
  go('notif', null);
  try {
    const j = await api('notifications');
    const box = document.getElementById('notifList');
    box.innerHTML = j.items.length ? '' : '<div class="empty">Nicio notificare inca.</div>';
    j.items.forEach(n => {
      box.innerHTML += '<div class="notif' + (n.seen ? '' : ' fresh') + '">' +
        avaHtml(n.name, n.avatar) +
        '<div><b>' + esc(n.text) + '</b><span>' + esc(n.at) + '</span></div>' +
        (n.otherId ? '<button class="back" onclick="openProfile(' + n.otherId + ')">Profil</button>' : '') +
        '</div>';
    });
    await api('notifications', 'POST', {});
    refreshBadges();
  } catch (e) { fail(e); }
}

/* clopotelul si bulina de pe tabul Chat */
async function refreshBadges() {
  if (!token || !me) return;
  try {
    const j = await api('notifications');
    const bell = document.getElementById('bell');
    document.getElementById('bellDot').textContent = j.unseen > 9 ? '9+' : j.unseen;
    bell.classList.toggle('on', j.unseen > 0);
    navBtn(3).classList.toggle('has-new', j.unread > 0);
  } catch (e) { /* ignoram, incercam data viitoare */ }
}

/* ================= RECUPERARE PAROLA ================= */

async function recoverPassword() {
  try {
    const j = await api('recover', 'POST', {
      identifier: v('recId'), code: v('recCode'),
      password: document.getElementById('recPwd').value
    });
    document.getElementById('recPwd').value = '';
    document.getElementById('recCode').value = '';
    showCode(j.code, 'Parola a fost schimbata');
    document.getElementById('loginId').value = v('recId');
  } catch (e) { fail(e); }
}

function showCode(code, title) {
  document.getElementById('codeValue').textContent = code;
  document.getElementById('codeTitle').textContent = title;
  show('code');
}
function copyCode() {
  const code = document.getElementById('codeValue').textContent;
  if (navigator.clipboard) {
    navigator.clipboard.writeText(code).then(
      () => showMsg('Codul a fost copiat.'),
      () => showMsg('Noteaza codul manual: ' + code));
  } else {
    showMsg('Noteaza codul manual: ' + code);
  }
}

document.getElementById('editBio').addEventListener('input', updateBioCount);
['recordSport', 'teammates', 'opponents'].forEach(id =>
  document.getElementById(id).addEventListener('input', calcLater));
document.getElementById('regUser').addEventListener('input', renderRegAvatar);
renderRegAvatar();

(async function init() {
  startTheme();
  if (!token) { show('login'); return; }
  try { await enterApp(); } catch (e) { logoutLocal(); }
})();
</script>
</body>
</html>
        """;

    /** Pagina intreaga. E impartita in trei bucati fiindca o constanta
     *  de tip text din Java nu poate depasi 64 KB. */
    private static final String PAGE = String.join("", PAGE_HEAD, PAGE_BODY, PAGE_JS);

    record Player(int id, String name, String availability, String zone, String avatar, String bio) {}
    record Scored(Player player, int points, int score) {}
    record Session(int userId, long expires) {}

    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private static final Properties cfg = new Properties();
    private static int port;
    private static Connection db;
    private static HttpServer server;

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
            try (Writer w = Files.newBufferedWriter(CFG_FILE, StandardCharsets.UTF_8)) {
                cfg.store(w, "SportMatch - setari server. Portul serverului se schimba aici.");
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
        addColumn("players", "avatar", "TEXT NOT NULL DEFAULT ''");
        addColumn("players", "bio", "TEXT NOT NULL DEFAULT ''");
        addColumn("users", "recovery_hash", "TEXT NOT NULL DEFAULT ''");
        addColumn("posts", "lat", "REAL NOT NULL DEFAULT 0");
        addColumn("posts", "lon", "REAL NOT NULL DEFAULT 0");
        addColumn("posts", "addr", "TEXT NOT NULL DEFAULT ''");
        addColumn("players", "start_place", "TEXT NOT NULL DEFAULT ''");
        addColumn("players", "start_lat", "REAL NOT NULL DEFAULT 0");
        addColumn("players", "start_lon", "REAL NOT NULL DEFAULT 0");
        addColumn("route_cache", "steps", "TEXT NOT NULL DEFAULT '[]'");
        seedDemoData();
    }

    /** Adauga o coloana doar daca lipseste (baze de date create inainte de actualizare). */
    private static void addColumn(String table, String column, String type) throws SQLException {
        boolean exists = false;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) exists = true;
            }
        }
        if (!exists) exec("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    /** Profile fictive si anunturi, o singura data pe baza de date. */
    private static void seedDemoData() throws SQLException {
        if (!scalarString("SELECT value FROM meta WHERE key = 'demo_seeded'").isEmpty()) return;

        db.setAutoCommit(false);
        try {
            int[] ids = new int[DEMO_PLAYERS.length];
            for (int i = 0; i < DEMO_PLAYERS.length; i++) {
                String[] d = DEMO_PLAYERS[i];
                Player existing = findPlayerByName(d[0]);
                if (existing != null) {
                    ids[i] = existing.id();
                    continue;
                }
                exec("INSERT INTO players (name, availability, zone, avatar, bio) VALUES (?, ?, ?, '', ?)",
                        d[0], d[2], d[1], d[3]);
                ids[i] = findPlayerByName(d[0]).id();
                int points = 20 + (i * 37) % 180;
                for (String sport : parseSports(d[4])) {
                    exec("INSERT OR IGNORE INTO player_sports (player_id, sport, points) VALUES (?, ?, ?)",
                            ids[i], sport, points);
                    points = Math.max(0, points - 25);
                }
            }
            for (String[] a : DEMO_POSTS) {
                int author = ids[Integer.parseInt(a[0])];
                exec("INSERT INTO posts (player_id, sport, title, place, when_text, slots, body) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        author, a[1], a[2], a[3], a[4], Integer.parseInt(a[5]), a[6]);
            }
            exec("INSERT OR REPLACE INTO meta (key, value) VALUES ('demo_seeded', '1')");
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
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
        server.setExecutor(Executors.newFixedThreadPool(4));
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
            case "POST /api/recover": return recoverPassword(p);
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
            case "GET /api/preview" -> previewMatch(me, p);
            case "GET /api/map" -> mapJson(me, p);
            case "GET /api/nav" -> navJson(p);
            case "GET /api/geocode" -> geocodeJson(p);
            case "POST /api/start" -> setStart(me, p);
            case "GET /api/profile" -> profileJson(me, p);
            case "POST /api/profile" -> updateProfile(me, p);
            case "GET /api/feed" -> feedJson(me, p);
            case "POST /api/post" -> addPost(me, p);
            case "POST /api/join" -> joinPost(me, p);
            case "GET /api/ranking" -> ranking(p);
            case "GET /api/chats" -> chats(me);
            case "GET /api/messages" -> messages(me, p);
            case "POST /api/message" -> sendMessage(me, p);
            case "GET /api/notifications" -> notifications(me);
            case "POST /api/notifications" -> markNotificationsSeen(me);
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
        String password = p.getOrDefault("password", "");
        String availability = p.getOrDefault("availability", "").trim();
        String zone = p.getOrDefault("zone", "").trim();
        String avatar = checkAvatar(p.getOrDefault("avatar", ""));

        if (!username.matches("[A-Za-z0-9_.]{3,20}")) {
            throw new IllegalArgumentException("Utilizatorul trebuie sa aiba 3-20 caractere: litere, cifre, _ sau .");
        }
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Adresa de email nu este valida.");
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

        String code = recoveryCode();
        db.setAutoCommit(false);
        try {
            exec("INSERT INTO players (name, availability, zone, avatar) VALUES (?, ?, ?, ?)",
                    username, availability, zone, avatar);
            int playerId = findPlayerByName(username).id();
            for (String s : sports) {
                exec("INSERT INTO player_sports (player_id, sport, points) VALUES (?, ?, 0)", playerId, s);
            }
            exec("INSERT INTO users (username, email, phone, password_hash, player_id, recovery_hash) "
                    + "VALUES (?, ?, ?, ?, ?, ?)",
                    username, email, "", hashPassword(password), playerId, hashPassword(code));
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
        return "{\"ok\":true,\"message\":" + q("Contul a fost creat.") + ",\"code\":" + q(code) + "}";
    }

    /** Cod de recuperare, 12 caractere usor de citit, grupate cate 4. */
    private static String recoveryCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(alphabet.charAt(RND.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** Resetare parola cu codul primit la crearea contului. Codul se schimba dupa folosire. */
    private static String recoverPassword(Map<String, String> p) throws SQLException {
        String identifier = req(p, "identifier");
        String code = req(p, "code").trim().toUpperCase();
        String password = p.getOrDefault("password", "");
        if (password.length() < 8) {
            throw new IllegalArgumentException("Parola noua trebuie sa aiba cel putin 8 caractere.");
        }

        int userId = -1;
        String stored = null;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id, recovery_hash FROM users WHERE username = ? OR email = ?")) {
            ps.setString(1, identifier);
            ps.setString(2, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    userId = rs.getInt(1);
                    stored = rs.getString(2);
                }
            }
        }
        if (stored == null || stored.isEmpty() || !checkPassword(code, stored)) {
            throw new IllegalArgumentException("Utilizatorul sau codul de recuperare nu sunt corecte.");
        }
        String fresh = recoveryCode();
        exec("UPDATE users SET password_hash = ?, recovery_hash = ? WHERE id = ?",
                hashPassword(password), hashPassword(fresh), userId);
        return "{\"ok\":true,\"message\":" + q("Parola a fost schimbata.") + ",\"code\":" + q(fresh) + "}";
    }



    private static String login(Map<String, String> p) throws SQLException {
        String identifier = req(p, "identifier");
        String password = p.getOrDefault("password", "");

        int userId = -1;
        String hash = null;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id, password_hash FROM users WHERE username = ? OR email = ?")) {
            ps.setString(1, identifier);
            ps.setString(2, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    userId = rs.getInt(1);
                    hash = rs.getString(2);
                }
            }
        }
        if (hash == null || !checkPassword(password, hash)) {
            throw new IllegalArgumentException("Utilizator sau parola gresite.");
        }
        String token = randomToken();
        sessions.put(token, new Session(userId, System.currentTimeMillis() + SESSION_MS));
        return "{\"ok\":true,\"message\":" + q("Autentificare reusita.") + ",\"token\":" + q(token) + "}";
    }

    private static String meJson(int userId, int me) throws SQLException {
        String username = scalarString("SELECT username FROM users WHERE id = ?", userId);
        String avatar = scalarString("SELECT avatar FROM players WHERE id = ?", me);
        return "{\"ok\":true,\"username\":" + q(username) + ",\"playerId\":" + me
                + ",\"avatar\":" + q(avatar) + ",\"message\":\"\"}";
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
                + ",\"availability\":" + q(p.availability())
                + ",\"avatar\":" + q(p.avatar()) + ",\"bio\":" + q(p.bio())
                + ",\"sports\":" + sports + "}";
    }

    // =====================================================================
    //  Pagina de profil
    // =====================================================================

    /** Profilul cerut (implicit al meu): date, statistici si anunturile proprii. */
    private static String profileJson(int me, Map<String, String> p) throws SQLException {
        int id = p.getOrDefault("id", "").isEmpty() ? me : intParam(p, "id");
        Player pl = playerById(id);

        int posts = scalarInt("SELECT COUNT(*) FROM posts WHERE player_id = ?", id);
        int played = scalarInt("SELECT COUNT(*) FROM match_log WHERE player_id = ?", id);
        int wins = scalarInt("SELECT COUNT(*) FROM match_log WHERE player_id = ? AND won = 1", id);
        int sports = sportPoints(id).size();
        int matched = matchesFor(id).size();

        return "{\"ok\":true,\"mine\":" + (id == me) + ",\"player\":" + playerJson(pl)
                + ",\"stats\":{\"posts\":" + posts + ",\"played\":" + played + ",\"wins\":" + wins
                + ",\"sports\":" + sports + ",\"matches\":" + matched + "}"
                + ",\"posts\":[" + String.join(",", postsOf(id, me)) + "],\"message\":\"\"}";
    }

    private static String updateProfile(int me, Map<String, String> p) throws SQLException {
        if (p.containsKey("bio")) {
            String bio = p.get("bio").trim();
            if (bio.length() > 400) throw new IllegalArgumentException("Descrierea poate avea cel mult 400 caractere.");
            exec("UPDATE players SET bio = ? WHERE id = ?", bio, me);
        }
        if (p.containsKey("avatar")) {
            exec("UPDATE players SET avatar = ? WHERE id = ?", checkAvatar(p.get("avatar")), me);
        }
        if (p.containsKey("zone")) exec("UPDATE players SET zone = ? WHERE id = ?", p.get("zone").trim(), me);
        if (p.containsKey("availability")) {
            exec("UPDATE players SET availability = ? WHERE id = ?", p.get("availability").trim(), me);
        }
        return ok("Profilul a fost salvat.");
    }

    // =====================================================================
    //  Anunturi de meciuri
    // =====================================================================

    /** Anunturile din feed, pe pagini, cele mai noi primele. */
    private static String feedJson(int me, Map<String, String> p) throws SQLException {
        int limit = Math.min(20, Math.max(1, parseIntOr(p.get("limit"), 8)));
        int offset = Math.max(0, parseIntOr(p.get("offset"), 0));
        String sport = p.getOrDefault("sport", "").trim().toLowerCase();
        boolean filtered = !sport.isEmpty();

        String sql = "SELECT po.id, po.player_id, pl.name, pl.avatar, po.sport, po.title, po.place, "
                + "po.when_text, po.slots, po.body, po.created_at FROM posts po "
                + "JOIN players pl ON pl.id = po.player_id "
                + (filtered ? "WHERE po.sport = ? " : "")
                + "ORDER BY po.id DESC LIMIT ? OFFSET ?";
        List<String> items = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            if (filtered) bind(ps, sport, limit, offset);
            else bind(ps, limit, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(postJson(rs, me));
            }
        }
        int total = filtered
                ? scalarInt("SELECT COUNT(*) FROM posts WHERE sport = ?", sport)
                : scalarInt("SELECT COUNT(*) FROM posts");
        return "{\"ok\":true,\"items\":[" + String.join(",", items) + "],\"total\":" + total
                + ",\"more\":" + (offset + items.size() < total) + ",\"message\":\"\"}";
    }

    private static List<String> postsOf(int playerId, int me) throws SQLException {
        List<String> items = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT po.id, po.player_id, pl.name, pl.avatar, po.sport, po.title, po.place, "
                        + "po.when_text, po.slots, po.body, po.created_at FROM posts po "
                        + "JOIN players pl ON pl.id = po.player_id "
                        + "WHERE po.player_id = ? ORDER BY po.id DESC")) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(postJson(rs, me));
            }
        }
        return items;
    }

    private static String postJson(ResultSet rs, int me) throws SQLException {
        int id = rs.getInt(1);
        int joined = scalarInt("SELECT COUNT(*) FROM post_joins WHERE post_id = ?", id);
        boolean mine = scalarInt("SELECT COUNT(*) FROM post_joins WHERE post_id = ? AND player_id = ?", id, me) > 0;
        return "{\"id\":" + id + ",\"authorId\":" + rs.getInt(2) + ",\"author\":" + q(rs.getString(3))
                + ",\"avatar\":" + q(rs.getString(4)) + ",\"sport\":" + q(rs.getString(5))
                + ",\"title\":" + q(rs.getString(6)) + ",\"place\":" + q(rs.getString(7))
                + ",\"when\":" + q(rs.getString(8)) + ",\"slots\":" + rs.getInt(9)
                + ",\"body\":" + q(rs.getString(10)) + ",\"createdAt\":" + q(rs.getString(11))
                + ",\"joined\":" + joined + ",\"joinedByMe\":" + mine
                + ",\"isMine\":" + (rs.getInt(2) == me) + "}";
    }

    private static String addPost(int me, Map<String, String> p) throws SQLException {
        String sport = req(p, "sport").toLowerCase();
        String title = req(p, "title");
        String place = req(p, "place");
        String when = req(p, "when");
        int slots = Math.max(0, parseIntOr(p.get("slots"), 1));
        String body = p.getOrDefault("body", "").trim();
        if (title.length() > 120) throw new IllegalArgumentException("Titlul poate avea cel mult 120 caractere.");
        if (body.length() > 600) throw new IllegalArgumentException("Textul poate avea cel mult 600 caractere.");

        exec("INSERT INTO posts (player_id, sport, title, place, when_text, slots, body) VALUES (?, ?, ?, ?, ?, ?, ?)",
                me, sport, title, place, when, slots, body);
        exec("INSERT OR IGNORE INTO player_sports (player_id, sport, points) VALUES (?, ?, 0)", me, sport);
        return ok("Anuntul a fost publicat.");
    }

    /** Ma inscriu sau renunt la un anunt. */
    private static String joinPost(int me, Map<String, String> p) throws SQLException {
        int postId = intParam(p, "post");
        if (scalarInt("SELECT COUNT(*) FROM posts WHERE id = ?", postId) == 0) {
            throw new IllegalArgumentException("Anuntul nu exista.");
        }
        if (scalarInt("SELECT player_id FROM posts WHERE id = ?", postId) == me) {
            throw new IllegalArgumentException("Este anuntul tau, ceilalti se inscriu la el.");
        }
        if (scalarInt("SELECT COUNT(*) FROM post_joins WHERE post_id = ? AND player_id = ?", postId, me) > 0) {
            exec("DELETE FROM post_joins WHERE post_id = ? AND player_id = ?", postId, me);
            return ok("Te-ai retras de la acest meci.");
        }
        exec("INSERT INTO post_joins (post_id, player_id) VALUES (?, ?)", postId, me);
        return ok("Te-ai inscris la meci. Organizatorul te vede in lista.");
    }


    // =====================================================================
    //  Harta: locatia terenului, punctul de plecare si traseul dintre ele
    //
    //  Aici folosim doua servicii web publice (API-uri REST, fara cont):
    //    - Nominatim (OpenStreetMap): adresa -> coordonate (geocodare) si
    //      coordonate -> adresa (geocodare inversa);
    //    - OSRM: traseul, distanta si durata dintre doua coordonate.
    //  Harta din pagina ia dalele de la serverul de dale OpenStreetMap.
    //  Fiecare raspuns se pastreaza in geo_cache / route_cache, ca sa nu
    //  cerem de doua ori acelasi lucru si sa respectam limitele serviciilor.
    // =====================================================================

    /** Un punct pe harta: coordonate si adresa scrisa cu litere. */
    private record Spot(double lat, double lon, String addr) {}

    /** Un drum intre doua puncte: cati km, cate minute, linia de pe harta
     *  si indicatiile pas cu pas (viraje), gata scrise in JSON. */
    private record Trip(double km, int minutes, String coords, String steps) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final String HTTP_UA = "SportMatch/1.0 (proiect studentesc)";
    private static final String NOMINATIM = "https://nominatim.openstreetmap.org";
    private static final String OSRM = "https://router.project-osrm.org";
    private static final String VALHALLA = "https://valhalla1.openstreetmap.de";
    private static final String ON_FOOT = "jos";

    /** Terenurile din anunturile demo, cu coordonatele lor din Timisoara. */
    private static final Map<String, Spot> KNOWN_PLACES = new HashMap<>();

    static {
        knownPlace("Baza sportiva Fabric", 45.7563, 21.2441);
        knownPlace("Teren Girocului", 45.7215, 21.2298);
        knownPlace("Teren Giroc", 45.7032, 21.2301);
        knownPlace("Parcul Studentesc", 45.7471, 21.2287);
        knownPlace("Complex Dumbravita", 45.8031, 21.2443);
        knownPlace("Teren Dumbravita 2", 45.8068, 21.2512);
        knownPlace("Sala Olimpia", 45.7571, 21.2231);
        knownPlace("Clubul Soarelui", 45.7281, 21.2292);
        knownPlace("Clubul Central", 45.7558, 21.2287);
        knownPlace("Teren Mehala", 45.7682, 21.1962);
        knownPlace("Sala Lipovei", 45.7733, 21.2261);
        knownPlace("Teren Lipovei", 45.7748, 21.2224);
        knownPlace("Parcul Rozelor", 45.7506, 21.2281);
        knownPlace("Padel Club Elisabetin", 45.7421, 21.2203);
        knownPlace("Sala Elisabetin", 45.7438, 21.2178);
        knownPlace("Baza Aradului", 45.7852, 21.2324);
        knownPlace("Padel Club Aradului", 45.7883, 21.2297);
        knownPlace("Sala Torontalului", 45.7801, 21.2131);
        knownPlace("Sala Iosefin", 45.7432, 21.2071);
        knownPlace("Bazin Termal", 45.7342, 21.2423);
        knownPlace("Stadionul Dan Paltinisanu", 45.7366, 21.2338);
    }

    private static void knownPlace(String name, double lat, double lon) {
        KNOWN_PLACES.put(name.toLowerCase(), new Spot(lat, lon, name + ", Timisoara"));
    }

    // ---------- apelul catre serviciile web ----------

    /** Cere o adresa web si intoarce textul raspunsului (JSON). */
    private static String httpGetText(String url) {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", HTTP_UA)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "ro")
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IllegalArgumentException("Serviciul de harti a raspuns cu codul " + resp.statusCode() + ".");
            }
            return resp.body();
        } catch (IOException e) {
            throw new IllegalArgumentException("Nu am ajuns la serviciul de harti. Verifica internetul.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Cererea catre serviciul de harti a fost intrerupta.");
        }
    }

    // ---------- un parser JSON mic, cat sa citim raspunsurile serviciilor ----------

    /** Transforma textul JSON in Map / List / String / Double / Boolean. */
    private static final class Json {
        private final String text;
        private int i;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String raw) {
            Json j = new Json(raw);
            j.skip();
            return j.value();
        }

        private void skip() {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        }

        private Object value() {
            if (i >= text.length()) return null;
            char c = text.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { i += 4; return Boolean.TRUE; }
            if (c == 'f') { i += 5; return Boolean.FALSE; }
            if (c == 'n') { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            skip();
            if (i < text.length() && text.charAt(i) == '}') { i++; return map; }
            while (i < text.length()) {
                skip();
                String key = string();
                skip();
                if (i < text.length() && text.charAt(i) == ':') i++;
                skip();
                map.put(key, value());
                skip();
                char c = i < text.length() ? text.charAt(i++) : '}';
                if (c == '}') break;
            }
            return map;
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++;
            skip();
            if (i < text.length() && text.charAt(i) == ']') { i++; return list; }
            while (i < text.length()) {
                skip();
                list.add(value());
                skip();
                char c = i < text.length() ? text.charAt(i++) : ']';
                if (c == ']') break;
            }
            return list;
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < text.length()) {
                char c = text.charAt(i++);
                if (c == '"') break;
                if (c != '\\') { sb.append(c); continue; }
                char e = text.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            }
            return sb.toString();
        }

        private Double number() {
            int start = i;
            while (i < text.length() && "-+.0123456789eE".indexOf(text.charAt(i)) >= 0) i++;
            try {
                return Double.valueOf(text.substring(start, i));
            } catch (RuntimeException e) {
                return 0.0;
            }
        }
    }

    /** Merge in adancime: at(raspuns, "routes", 0, "distance"). */
    private static Object at(Object node, Object... path) {
        Object current = node;
        for (Object key : path) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(String.valueOf(key));
            } else if (current instanceof List<?> list && key instanceof Integer index) {
                current = index >= 0 && index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
            if (current == null) return null;
        }
        return current;
    }

    private static String str(Object node, Object... path) {
        Object found = at(node, path);
        return found == null ? "" : String.valueOf(found);
    }

    private static double dbl(Object node, Object... path) {
        Object found = at(node, path);
        if (found instanceof Number number) return number.doubleValue();
        return parseDoubleOr(String.valueOf(found), 0);
    }

    private static List<Object> items(Object node, Object... path) {
        Object found = at(node, path);
        return found instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    // ---------- geocodare ----------

    /** Adresa scrisa de om -> coordonate. Mai intai din cache, apoi de la Nominatim. */
    private static Spot geocode(String raw) throws SQLException {
        String query = raw == null ? "" : raw.trim();
        if (query.isEmpty()) throw new IllegalArgumentException("Scrie un loc sau o adresa.");
        String key = "q:" + query.toLowerCase();
        Spot cached = cacheGet(key);
        if (cached != null) return cached;

        Spot known = KNOWN_PLACES.get(query.toLowerCase());
        if (known != null) {
            cachePut(key, known);
            return known;
        }
        Spot found = nominatimSearch(query);
        if (found == null && !query.toLowerCase().contains("timisoara")) {
            found = nominatimSearch(query + ", Timisoara, Romania");
        }
        if (found == null) {
            throw new IllegalArgumentException("Nu am gasit locul \"" + query + "\". Scrie strada si orasul.");
        }
        cachePut(key, found);
        return found;
    }

    private static Spot nominatimSearch(String query) {
        Object root = Json.parse(httpGetText(NOMINATIM + "/search?format=jsonv2&limit=1&q=" + enc(query)));
        List<Object> found = root instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        if (found.isEmpty()) return null;
        Object first = found.get(0);
        String addr = str(first, "display_name");
        return new Spot(dbl(first, "lat"), dbl(first, "lon"), addr.isEmpty() ? query : addr);
    }

    /** Coordonate -> adresa scrisa cu litere (geocodare inversa). */
    private static Spot reverseGeocode(double lat, double lon) throws SQLException {
        String key = String.format(Locale.US, "r:%.5f,%.5f", lat, lon);
        Spot cached = cacheGet(key);
        if (cached != null) return cached;
        Object root = Json.parse(httpGetText(String.format(Locale.US,
                NOMINATIM + "/reverse?format=jsonv2&lat=%.6f&lon=%.6f", lat, lon)));
        String addr = str(root, "display_name");
        Spot spot = new Spot(lat, lon, addr.isEmpty() ? coordLabel(lat, lon) : addr);
        cachePut(key, spot);
        return spot;
    }

    /** Adresa locatiei, iar daca serviciul nu raspunde, doar coordonatele. */
    private static Spot describe(double lat, double lon, String label) throws SQLException {
        if (label != null && !label.isBlank()) return new Spot(lat, lon, label.trim());
        try {
            return reverseGeocode(lat, lon);
        } catch (RuntimeException e) {
            return new Spot(lat, lon, coordLabel(lat, lon));
        }
    }

    private static Spot cacheGet(String key) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT lat, lon, addr FROM geo_cache WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Spot(rs.getDouble(1), rs.getDouble(2), rs.getString(3));
            }
        }
        return null;
    }

    private static void cachePut(String key, Spot spot) throws SQLException {
        exec("INSERT OR REPLACE INTO geo_cache (key, lat, lon, addr) VALUES (?, ?, ?, ?)",
                key, spot.lat(), spot.lon(), spot.addr());
    }

    // ---------- traseul ----------

    /** Cu masina intrebam OSRM, pe jos intrebam Valhalla: serverul public
     *  de la OSRM raspunde la fel pentru orice profil, deci ar da drumuri
     *  de masina si pietonilor. Valhalla stie trotuare si treceri. */
    private static Trip routeFor(Spot from, Spot to, String mode, boolean simplified) {
        return ON_FOOT.equals(mode)
                ? valhallaAsk(from, to)
                : osrmAsk(from, to, simplified);
    }

    /** Traseul de pe harta, pastrat in cache. */
    private static Trip cachedRoute(Spot from, Spot to, String mode) throws SQLException {
        String key = String.format(Locale.US, "%s:%.4f,%.4f>%.4f,%.4f",
                mode, from.lat(), from.lon(), to.lat(), to.lon());
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT km, minutes, coords, steps FROM route_cache WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Trip(rs.getDouble(1), rs.getInt(2), rs.getString(3), rs.getString(4));
                }
            }
        }
        Trip trip = routeFor(from, to, mode, true);
        exec("INSERT OR REPLACE INTO route_cache (key, km, minutes, coords, steps) VALUES (?, ?, ?, ?, ?)",
                key, trip.km(), trip.minutes(), trip.coords(), trip.steps());
        return trip;
    }

    /** Intrebarea propriu-zisa catre OSRM, cu tot cu virajele de pe drum. */
    private static Trip osrmAsk(Spot from, Spot to, boolean simplified) {
        Object root = Json.parse(httpGetText(String.format(Locale.US,
                OSRM + "/route/v1/driving/%.6f,%.6f;%.6f,%.6f"
                        + "?overview=%s&geometries=geojson&steps=true&alternatives=false",
                from.lon(), from.lat(), to.lon(), to.lat(), simplified ? "simplified" : "full")));
        Object route = at(root, "routes", 0);
        if (route == null) {
            throw new IllegalArgumentException("Serviciul de trasee nu a gasit un drum intre cele doua puncte.");
        }
        StringBuilder line = new StringBuilder("[");
        for (Object point : items(route, "geometry", "coordinates")) {
            if (line.length() > 1) line.append(",");
            line.append("[").append(coord(dbl(point, 0))).append(",").append(coord(dbl(point, 1))).append("]");
        }
        line.append("]");

        StringBuilder steps = new StringBuilder("[");
        for (Object step : items(route, "legs", 0, "steps")) {
            Object maneuver = at(step, "maneuver");
            String type = str(maneuver, "type");
            String modifier = str(maneuver, "modifier");
            String street = str(step, "name");
            int exit = (int) dbl(maneuver, "exit");
            if (steps.length() > 1) steps.append(",");
            String text = stepText(type, modifier, street, exit);
            steps.append("{\"text\":").append(q(text))
                    .append(",\"spoken\":").append(q(text))
                    .append(",\"street\":").append(q(street))
                    .append(",\"type\":").append(q(type))
                    .append(",\"modifier\":").append(q(modifier))
                    .append(",\"meters\":").append((int) Math.round(dbl(step, "distance")))
                    .append(",\"lat\":").append(coord(dbl(maneuver, "location", 1)))
                    .append(",\"lon\":").append(coord(dbl(maneuver, "location", 0)))
                    .append("}");
        }
        steps.append("]");

        return new Trip(dbl(route, "distance") / 1000.0,
                (int) Math.round(dbl(route, "duration") / 60.0),
                line.toString(), steps.toString());
    }


    /** Traseul pe jos, de la Valhalla (instanta publica a OpenStreetMap).
     *  Indicatiile vin direct in romana, cu trotuare si treceri de pietoni. */
    private static Trip valhallaAsk(Spot from, Spot to) {
        String query = "{\"locations\":[{\"lat\":" + coord(from.lat()) + ",\"lon\":" + coord(from.lon())
                + "},{\"lat\":" + coord(to.lat()) + ",\"lon\":" + coord(to.lon())
                + "}],\"costing\":\"pedestrian\""
                + ",\"directions_options\":{\"language\":\"ro-RO\",\"units\":\"kilometers\"}}";
        Object root = Json.parse(httpGetText(VALHALLA + "/route?json=" + enc(query)));
        Object leg = at(root, "trip", "legs", 0);
        if (leg == null) {
            throw new IllegalArgumentException("Serviciul de trasee nu a gasit un drum pe jos.");
        }
        List<double[]> shape = decodeShape(str(leg, "shape"));

        StringBuilder line = new StringBuilder("[");
        for (double[] point : shape) {
            if (line.length() > 1) line.append(",");
            line.append("[").append(coord(point[1])).append(",").append(coord(point[0])).append("]");
        }
        line.append("]");

        StringBuilder steps = new StringBuilder("[");
        for (Object move : items(leg, "maneuvers")) {
            int begin = (int) dbl(move, "begin_shape_index");
            double[] where = begin >= 0 && begin < shape.size()
                    ? shape.get(begin)
                    : new double[]{to.lat(), to.lon()};
            String text = tidyFoot(str(move, "instruction"));
            String spoken = tidyFoot(str(move, "verbal_pre_transition_instruction"));
            if (steps.length() > 1) steps.append(",");
            steps.append("{\"text\":").append(q(text))
                    .append(",\"spoken\":").append(q(spoken.isEmpty() ? text : spoken))
                    .append(",\"street\":").append(q(""))
                    .append(",\"type\":").append(q("walk"))
                    .append(",\"modifier\":").append(q(footTurn((int) dbl(move, "type"))))
                    .append(",\"meters\":").append((int) Math.round(dbl(move, "length") * 1000))
                    .append(",\"lat\":").append(coord(where[0]))
                    .append(",\"lon\":").append(coord(where[1]))
                    .append("}");
        }
        steps.append("]");

        return new Trip(dbl(root, "trip", "summary", "length"),
                (int) Math.round(dbl(root, "trip", "summary", "time") / 60.0),
                line.toString(), steps.toString());
    }

    /** Valhalla lasa netraduse numele generice ale drumurilor de pietoni. */
    private static final String[][] FOOT_WORDS = {
        {"the crosswalk", "trecerea de pietoni"},
        {"the pedestrian crossing", "trecerea de pietoni"},
        {"the sidewalk", "trotuar"},
        {"the walkway", "alee"},
        {"the footway", "alee"},
        {"the path", "poteca"},
        {"the track", "drumul de pamant"},
        {"the stairs", "scari"},
        {"the steps", "trepte"},
        {"the footbridge", "pasarela"},
        {"the alley", "aleea"},
        {"the cycleway", "pista de biciclete"},
    };

    /** Inlocuim numele acelea cu varianta romaneasca. */
    private static String tidyFoot(String text) {
        String out = text;
        for (String[] pair : FOOT_WORDS) out = out.replace(pair[0], pair[1]);
        return out;
    }

    /** Valhalla da felul virajului ca numar; il traducem in stanga / dreapta. */
    private static String footTurn(int type) {
        return switch (type) {
            case 9, 10, 11, 18, 20, 23 -> "right";
            case 14, 15, 16, 19, 21, 24 -> "left";
            case 12, 13 -> "uturn";
            default -> "straight";
        };
    }

    /** Valhalla trimite traseul ca sir comprimat (polyline cu 6 zecimale). */
    private static List<double[]> decodeShape(String encoded) {
        List<double[]> points = new ArrayList<>();
        int index = 0;
        int lat = 0;
        int lon = 0;
        while (index < encoded.length()) {
            int shift = 0;
            int result = 0;
            int b;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && index < encoded.length());
            lat += (result & 1) != 0 ? ~(result >> 1) : result >> 1;
            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && index < encoded.length());
            lon += (result & 1) != 0 ? ~(result >> 1) : result >> 1;
            points.add(new double[]{lat / 1e6, lon / 1e6});
        }
        return points;
    }

    /** Indicatia scrisa pe romaneste pentru un viraj primit de la OSRM. */
    private static String stepText(String type, String modifier, String name, int exit) {
        String street = name == null ? "" : name.trim();
        String on = street.isEmpty() ? "" : " pe " + street;
        return switch (type) {
            case "depart" -> street.isEmpty() ? "Porneste la drum" : "Porneste pe " + street;
            case "arrive" -> "Ai ajuns la destinatie";
            case "roundabout", "rotary", "roundabout turn" -> exit > 0
                    ? "In sensul giratoriu, iesi pe iesirea " + exit + on
                    : "Intra in sensul giratoriu";
            case "merge" -> "Intra" + (street.isEmpty() ? " pe drum" : on);
            case "on ramp" -> "Intra pe rampa" + on;
            case "off ramp" -> "Iesi de pe drum" + on;
            case "fork" -> "La bifurcatie tine " + direction(modifier) + on;
            case "end of road" -> "La capatul strazii o iei " + direction(modifier) + on;
            case "new name", "continue", "notification" -> "Continua" + (street.isEmpty() ? " inainte" : on);
            default -> {
                if ("uturn".equals(modifier)) yield "Intoarce-te" + on;
                if ("straight".equals(modifier)) yield "Mergi drept inainte" + on;
                yield "Vireaza " + direction(modifier) + on;
            }
        };
    }

    private static String direction(String modifier) {
        if (modifier == null) return "";
        return switch (modifier) {
            case "left" -> "la stanga";
            case "right" -> "la dreapta";
            case "slight left" -> "usor la stanga";
            case "slight right" -> "usor la dreapta";
            case "sharp left" -> "strans la stanga";
            case "sharp right" -> "strans la dreapta";
            case "straight" -> "drept inainte";
            case "uturn" -> "in sens invers";
            default -> "";
        };
    }

    /** Distanta in linie dreapta, cand serviciul de trasee nu raspunde. */
    private static double straightKm(Spot a, Spot b) {
        double r = 6371.0;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    // ---------- rutele din API ----------

    /** Harta unui anunt: terenul, punctul de plecare si traseul dintre ele. */
    private static String mapJson(int me, Map<String, String> p) throws SQLException {
        int postId = intParam(p, "post");
        String mode = travelMode(p);
        String title;
        String place;
        String when;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT title, place, when_text FROM posts WHERE id = ?")) {
            ps.setInt(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Anuntul nu exista.");
                title = rs.getString(1);
                place = rs.getString(2);
                when = rs.getString(3);
            }
        }
        Spot dest = postSpot(postId, place);

        String fromLat = p.getOrDefault("fromLat", "").trim();
        String fromLon = p.getOrDefault("fromLon", "").trim();
        String fromText = p.getOrDefault("from", "").trim();
        Spot from;
        if (!fromLat.isEmpty() && !fromLon.isEmpty()) {
            from = describe(parseDoubleOr(fromLat, 0), parseDoubleOr(fromLon, 0), fromText);
        } else if (!fromText.isEmpty()) {
            from = geocode(fromText);
        } else {
            from = savedStart(me);
        }

        String trip = "null";
        String note = "";
        if (from != null) {
            try {
                Trip t = cachedRoute(from, dest, mode);
                trip = "{\"km\":" + num(t.km()) + ",\"minutes\":" + t.minutes()
                        + ",\"coords\":" + t.coords() + ",\"steps\":" + t.steps() + "}";
            } catch (RuntimeException e) {
                note = e.getMessage() + " Arat distanta in linie dreapta.";
                trip = "{\"km\":" + num(straightKm(from, dest)) + ",\"minutes\":0,\"coords\":[],\"steps\":[]}";
            }
        }
        return "{\"ok\":true,\"post\":" + postId + ",\"mode\":" + q(mode) + ",\"title\":" + q(title)
                + ",\"place\":" + q(place) + ",\"when\":" + q(when)
                + ",\"dest\":" + spotJson(dest)
                + ",\"from\":" + (from == null ? "null" : spotJson(from))
                + ",\"trip\":" + trip + ",\"note\":" + q(note) + ",\"message\":\"\"}";
    }


    /** Navigarea in timp real: traseul de la pozitia de acum pana la teren.
     *  Telefonul trimite coordonatele la fiecare recalculare, noi cerem
     *  de la OSRM traseul proaspat si virajele pana la destinatie. */
    private static String navJson(Map<String, String> p) throws SQLException {
        int postId = intParam(p, "post");
        String place;
        try (PreparedStatement ps = db.prepareStatement("SELECT place FROM posts WHERE id = ?")) {
            ps.setInt(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Anuntul nu exista.");
                place = rs.getString(1);
            }
        }
        Spot dest = postSpot(postId, place);
        String lat = p.getOrDefault("lat", "").trim();
        String lon = p.getOrDefault("lon", "").trim();
        if (lat.isEmpty() || lon.isEmpty()) {
            throw new IllegalArgumentException("Lipseste pozitia ta.");
        }
        Spot here = new Spot(parseDoubleOr(lat, 0), parseDoubleOr(lon, 0), "");
        double left = straightKm(here, dest);
        if (left < 0.06) {
            return "{\"ok\":true,\"arrived\":true,\"dest\":" + spotJson(dest)
                    + ",\"trip\":null,\"message\":\"Ai ajuns la teren.\"}";
        }
        Trip trip = routeFor(here, dest, travelMode(p), false);
        return "{\"ok\":true,\"arrived\":false,\"dest\":" + spotJson(dest)
                + ",\"trip\":{\"km\":" + num(trip.km()) + ",\"minutes\":" + trip.minutes()
                + ",\"coords\":" + trip.coords() + ",\"steps\":" + trip.steps()
                + "},\"message\":\"\"}";
    }

    /** Cum ajungi: cu masina (implicit) sau pe jos. */
    private static String travelMode(Map<String, String> p) {
        return ON_FOOT.equals(p.getOrDefault("mode", "").trim()) ? ON_FOOT : "masina";
    }

    /** Coordonatele terenului din anunt, geocodate la prima deschidere a hartii. */
    private static Spot postSpot(int postId, String place) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT lat, lon, addr FROM posts WHERE id = ?")) {
            ps.setInt(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double lat = rs.getDouble(1);
                    double lon = rs.getDouble(2);
                    if (lat != 0 || lon != 0) {
                        String addr = rs.getString(3);
                        return new Spot(lat, lon, addr.isEmpty() ? place : addr);
                    }
                }
            }
        }
        Spot spot = geocode(place);
        exec("UPDATE posts SET lat = ?, lon = ?, addr = ? WHERE id = ?",
                spot.lat(), spot.lon(), spot.addr(), postId);
        return spot;
    }

    /** Punctul de plecare salvat in profil, sau null daca nu e setat. */
    private static Spot savedStart(int me) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT start_place, start_lat, start_lon FROM players WHERE id = ?")) {
            ps.setInt(1, me);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double lat = rs.getDouble(2);
                    double lon = rs.getDouble(3);
                    if (lat != 0 || lon != 0) {
                        String label = rs.getString(1);
                        return new Spot(lat, lon, label.isEmpty() ? coordLabel(lat, lon) : label);
                    }
                }
            }
        }
        return null;
    }

    /** Salvez punctul de plecare implicit, ca sa nu-l scriu la fiecare anunt. */
    private static String setStart(int me, Map<String, String> p) throws SQLException {
        String lat = p.getOrDefault("fromLat", "").trim();
        String lon = p.getOrDefault("fromLon", "").trim();
        String text = p.getOrDefault("from", "").trim();
        Spot spot = (!lat.isEmpty() && !lon.isEmpty())
                ? describe(parseDoubleOr(lat, 0), parseDoubleOr(lon, 0), text)
                : geocode(text);
        exec("UPDATE players SET start_place = ?, start_lat = ?, start_lon = ? WHERE id = ?",
                spot.addr(), spot.lat(), spot.lon(), me);
        return "{\"ok\":true,\"from\":" + spotJson(spot)
                + ",\"message\":\"Punctul de plecare a fost salvat.\"}";
    }

    /** Geocodare la cerere: ?q=adresa, sau ?lat=&lon= pentru adresa unei locatii. */
    private static String geocodeJson(Map<String, String> p) throws SQLException {
        String lat = p.getOrDefault("lat", "").trim();
        String lon = p.getOrDefault("lon", "").trim();
        Spot spot = (!lat.isEmpty() && !lon.isEmpty())
                ? reverseGeocode(parseDoubleOr(lat, 0), parseDoubleOr(lon, 0))
                : geocode(p.getOrDefault("q", ""));
        return "{\"ok\":true,\"spot\":" + spotJson(spot) + ",\"message\":\"\"}";
    }

    private static String spotJson(Spot spot) {
        return "{\"lat\":" + coord(spot.lat()) + ",\"lon\":" + coord(spot.lon())
                + ",\"addr\":" + q(spot.addr()) + "}";
    }

    private static String coord(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private static String coordLabel(double lat, double lon) {
        return String.format(Locale.US, "Locatie %.5f, %.5f", lat, lon);
    }

    private static String enc(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static double parseDoubleOr(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Poza de profil: goala sau o imagine trimisa ca data URL, de cel mult 400 KB. */
    private static String checkAvatar(String raw) {
        String avatar = raw == null ? "" : raw.trim();
        if (avatar.isEmpty()) return "";
        if (!avatar.startsWith("data:image/")) {
            throw new IllegalArgumentException("Poza de profil nu este o imagine valida.");
        }
        if (avatar.length() > 400_000) {
            throw new IllegalArgumentException("Poza este prea mare. Alege o imagine mai mica.");
        }
        return avatar;
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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
        Player mine = playerById(me);
        boolean isNew = scalarInt("SELECT COUNT(*) FROM likes WHERE from_id = ? AND to_id = ?", me, to) == 0;
        exec("INSERT OR IGNORE INTO likes (from_id, to_id) VALUES (?, ?)", me, to);

        if (isMutual(me, to)) {
            if (isNew) {
                notify(to, "match", me, "Potrivire cu " + mine.name() + "! Acum va puteti scrie.");
                notify(me, "match", to, "Potrivire cu " + target.name() + "! Acum va puteti scrie.");
            }
            return ok("Potrivire! Tu si " + target.name() + " va puteti scrie din tabul Chat.");
        }
        if (isNew) notify(to, "like", me, mine.name() + " te-a apreciat.");
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

    /**
     * Cat valoreaza meciul inainte sa il joci: nivelul fiecarui jucator,
     * media pe echipe si punctele castigate sau pierdute.
     */
    private static String previewMatch(int me, Map<String, String> p) throws SQLException {
        String sport = p.getOrDefault("sport", "").trim().toLowerCase();
        if (sport.isEmpty()) throw new IllegalArgumentException("Scrie sportul ca sa vezi calculul.");
        if (!hasSport(me, sport)) {
            throw new IllegalArgumentException("Sportul nu este in profilul tau. Adauga-l mai intai.");
        }

        List<String> unknown = new ArrayList<>();
        List<Player> teammates = resolveSoft(p.getOrDefault("teammates", ""), me, sport, unknown);
        List<Player> opponents = resolveSoft(p.getOrDefault("opponents", ""), me, sport, unknown);

        int ownPoints = pointsOf(me, sport);
        int ownLevel = levelFromPoints(ownPoints);
        double teamAvg = teammates.isEmpty() ? ownLevel : averageLevel(teammates, sport);
        double oppAvg = opponents.isEmpty() ? ownLevel : averageLevel(opponents, sport);
        int win = computePoints(true, ownLevel, teamAvg, oppAvg);
        int loss = computePoints(false, ownLevel, teamAvg, oppAvg);

        return "{\"ok\":true,\"sport\":" + q(sport)
                + ",\"me\":" + sideJson(playerById(me), sport)
                + ",\"team\":" + sideListJson(teammates, sport)
                + ",\"opponents\":" + sideListJson(opponents, sport)
                + ",\"teamAvg\":" + num(teamAvg)
                + ",\"oppAvg\":" + num(oppAvg)
                + ",\"ownLevel\":" + ownLevel
                + ",\"ownPoints\":" + ownPoints
                + ",\"win\":" + win
                + ",\"loss\":" + loss
                + ",\"afterWin\":" + Math.max(0, ownPoints + win)
                + ",\"afterLoss\":" + Math.max(0, ownPoints + loss)
                + ",\"unknown\":" + jsonList(unknown) + ",\"message\":\"\"}";
    }

    private static String sideJson(Player pl, String sport) throws SQLException {
        int points = pointsOf(pl.id(), sport);
        return "{\"id\":" + pl.id() + ",\"name\":" + q(pl.name()) + ",\"avatar\":" + q(pl.avatar())
                + ",\"points\":" + points + ",\"levelNo\":" + levelFromPoints(points)
                + ",\"level\":" + q(LEVELS[levelFromPoints(points)]) + "}";
    }

    private static String sideListJson(List<Player> list, String sport) throws SQLException {
        List<String> items = new ArrayList<>();
        for (Player pl : list) items.add(sideJson(pl, sport));
        return "[" + String.join(",", items) + "]";
    }

    /** Ca resolveNames, dar numele gresite sunt doar raportate, nu opresc calculul. */
    private static List<Player> resolveSoft(String raw, int me, String sport, List<String> unknown)
            throws SQLException {
        List<Player> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        for (String n : raw.split(",")) {
            String name = n.trim();
            if (name.isEmpty()) continue;
            Player pl = findPlayerByName(name);
            if (pl == null || pl.id() == me || !hasSport(pl.id(), sport)) {
                if (!unknown.contains(name)) unknown.add(name);
                continue;
            }
            boolean duplicate = false;
            for (Player x : list) if (x.id() == pl.id()) duplicate = true;
            if (!duplicate) list.add(pl);
        }
        return list;
    }

    /** Numar zecimal cu punct, indiferent de setarile regionale. */
    private static String num(double value) {
        return String.format(Locale.US, "%.2f", value);
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
    //  Clasament
    // =====================================================================

    /** Top jucatori la un sport, optional doar dintr-o zona. */
    private static String ranking(Map<String, String> p) throws SQLException {
        List<String> sports = textColumn("SELECT DISTINCT sport FROM player_sports ORDER BY sport");
        List<String> zones = textColumn("SELECT DISTINCT zone FROM players WHERE zone <> '' ORDER BY zone");

        String sport = p.getOrDefault("sport", "").trim().toLowerCase();
        if (sport.isEmpty()) sport = sports.isEmpty() ? "" : sports.get(0);
        String zone = p.getOrDefault("zone", "").trim();

        List<String> items = new ArrayList<>();
        String sql = "SELECT pl.id, pl.name, pl.avatar, pl.zone, s.points FROM player_sports s "
                + "JOIN players pl ON pl.id = s.player_id WHERE s.sport = ?"
                + (zone.isEmpty() ? "" : " AND pl.zone = ? COLLATE NOCASE")
                + " ORDER BY s.points DESC, pl.name LIMIT 50";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            if (zone.isEmpty()) bind(ps, sport);
            else bind(ps, sport, zone);
            try (ResultSet rs = ps.executeQuery()) {
                int rank = 0;
                while (rs.next()) {
                    rank++;
                    int points = rs.getInt(5);
                    items.add("{\"rank\":" + rank + ",\"id\":" + rs.getInt(1)
                            + ",\"name\":" + q(rs.getString(2))
                            + ",\"avatar\":" + q(rs.getString(3))
                            + ",\"zone\":" + q(rs.getString(4))
                            + ",\"points\":" + points
                            + ",\"level\":" + q(LEVELS[levelFromPoints(points)])
                            + ",\"played\":" + scalarInt(
                                    "SELECT COUNT(*) FROM match_log WHERE player_id = ? AND sport = ?",
                                    rs.getInt(1), sport)
                            + "}");
                }
            }
        }
        return "{\"ok\":true,\"sport\":" + q(sport) + ",\"zone\":" + q(zone)
                + ",\"sports\":" + jsonList(sports) + ",\"zones\":" + jsonList(zones)
                + ",\"items\":[" + String.join(",", items) + "],\"message\":\"\"}";
    }

    // =====================================================================
    //  Mesaje intre jucatorii potriviti
    // =====================================================================

    /** Conversatiile mele: doar cu jucatorii cu care m-am potrivit. */
    private static String chats(int me) throws SQLException {
        List<String> items = new ArrayList<>();
        for (Player o : matchesFor(me)) {
            String last = "";
            String lastAt = "";
            try (PreparedStatement ps = db.prepareStatement(
                    "SELECT body, created_at FROM messages "
                            + "WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?) "
                            + "ORDER BY id DESC LIMIT 1")) {
                bind(ps, me, o.id(), o.id(), me);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        last = rs.getString(1);
                        lastAt = rs.getString(2);
                    }
                }
            }
            int unread = scalarInt(
                    "SELECT COUNT(*) FROM messages WHERE from_id = ? AND to_id = ? AND seen = 0", o.id(), me);
            items.add("{\"id\":" + o.id() + ",\"name\":" + q(o.name()) + ",\"avatar\":" + q(o.avatar())
                    + ",\"zone\":" + q(o.zone()) + ",\"availability\":" + q(o.availability())
                    + ",\"last\":" + q(last) + ",\"lastAt\":" + q(lastAt) + ",\"unread\":" + unread + "}");
        }
        return okItems(items);
    }

    /** Mesajele dintr-o conversatie; cele primite sunt marcate ca citite. */
    private static String messages(int me, Map<String, String> p) throws SQLException {
        int other = intParam(p, "with");
        requireMatch(me, other);
        exec("UPDATE messages SET seen = 1 WHERE from_id = ? AND to_id = ?", other, me);

        List<String> items = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id, from_id, body, created_at FROM messages "
                        + "WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?) "
                        + "ORDER BY id")) {
            bind(ps, me, other, other, me);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add("{\"id\":" + rs.getInt(1) + ",\"mine\":" + (rs.getInt(2) == me)
                            + ",\"body\":" + q(rs.getString(3))
                            + ",\"at\":" + q(rs.getString(4)) + "}");
                }
            }
        }
        Player o = playerById(other);
        return "{\"ok\":true,\"with\":{\"id\":" + o.id() + ",\"name\":" + q(o.name())
                + ",\"avatar\":" + q(o.avatar()) + "},\"items\":[" + String.join(",", items)
                + "],\"message\":\"\"}";
    }

    private static String sendMessage(int me, Map<String, String> p) throws SQLException {
        int other = intParam(p, "to");
        requireMatch(me, other);
        String body = req(p, "body");
        if (body.length() > 500) throw new IllegalArgumentException("Mesajul poate avea cel mult 500 caractere.");
        exec("INSERT INTO messages (from_id, to_id, body) VALUES (?, ?, ?)", me, other, body);
        return ok("Mesaj trimis.");
    }

    private static void requireMatch(int me, int other) throws SQLException {
        if (other == me) throw new IllegalArgumentException("Nu iti poti scrie tie.");
        if (!isMutual(me, other)) {
            throw new IllegalArgumentException("Puteti vorbi doar dupa ce va apreciati reciproc.");
        }
    }

    // =====================================================================
    //  Notificari
    // =====================================================================

    private static void notify(int playerId, String kind, int otherId, String text) throws SQLException {
        exec("INSERT INTO notifications (player_id, kind, other_id, text) VALUES (?, ?, ?, ?)",
                playerId, kind, otherId, text);
    }

    private static String notifications(int me) throws SQLException {
        List<String> items = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT n.id, n.kind, n.other_id, n.text, n.seen, n.created_at, "
                        + "COALESCE(pl.name, ''), COALESCE(pl.avatar, '') FROM notifications n "
                        + "LEFT JOIN players pl ON pl.id = n.other_id "
                        + "WHERE n.player_id = ? ORDER BY n.id DESC LIMIT 50")) {
            ps.setInt(1, me);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add("{\"id\":" + rs.getInt(1) + ",\"kind\":" + q(rs.getString(2))
                            + ",\"otherId\":" + rs.getInt(3) + ",\"text\":" + q(rs.getString(4))
                            + ",\"seen\":" + (rs.getInt(5) == 1) + ",\"at\":" + q(rs.getString(6))
                            + ",\"name\":" + q(rs.getString(7)) + ",\"avatar\":" + q(rs.getString(8)) + "}");
                }
            }
        }
        int unseen = scalarInt("SELECT COUNT(*) FROM notifications WHERE player_id = ? AND seen = 0", me);
        int unread = scalarInt("SELECT COUNT(*) FROM messages WHERE to_id = ? AND seen = 0", me);
        return "{\"ok\":true,\"items\":[" + String.join(",", items) + "],\"unseen\":" + unseen
                + ",\"unread\":" + unread + ",\"message\":\"\"}";
    }

    private static String markNotificationsSeen(int me) throws SQLException {
        exec("UPDATE notifications SET seen = 1 WHERE player_id = ?", me);
        return ok("");
    }

    // =====================================================================
    //  Ajutoare HTTP / JSON
    // =====================================================================

    private static List<String> textColumn(String sql, Object... args) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static String jsonList(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String v : values) quoted.add(q(v));
        return "[" + String.join(",", quoted) + "]";
    }

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

    private static final String PLAYER_COLS = "SELECT id, name, availability, zone, avatar, bio";
    private static final String PLAYER_COLS_P =
            "SELECT p.id, p.name, p.availability, p.zone, p.avatar, p.bio";

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
                    out.add(new Player(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6)));
                }
            }
        }
        return out;
    }

    private static List<Player> allPlayers() throws SQLException {
        return queryPlayers(PLAYER_COLS + " FROM players ORDER BY name");
    }

    private static Player findPlayerByName(String name) throws SQLException {
        List<Player> found = queryPlayers(PLAYER_COLS + " FROM players WHERE name = ?", name.trim());
        return found.isEmpty() ? null : found.get(0);
    }

    private static Player playerById(int id) throws SQLException {
        List<Player> found = queryPlayers(PLAYER_COLS + " FROM players WHERE id = ?", id);
        if (found.isEmpty()) throw new IllegalArgumentException("Profilul nu exista.");
        return found.get(0);
    }

    private static List<Player> playersWithSport(String sport, int excludeId) throws SQLException {
        return queryPlayers(PLAYER_COLS_P + " FROM players p "
                + "JOIN player_sports s ON s.player_id = p.id "
                + "WHERE s.sport = ? AND p.id <> ? ORDER BY p.id", sport, excludeId);
    }

    private static List<Player> matchesFor(int id) throws SQLException {
        return queryPlayers(PLAYER_COLS_P + " FROM players p "
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


        JButton stop = new JButton("Opreste serverul");
        stop.addActionListener(e -> System.exit(0));

        JButton recovery = new JButton("Cod de recuperare pentru un cont");
        recovery.addActionListener(e -> {
            String who = JOptionPane.showInputDialog(f,
                    "Utilizatorul sau emailul contului care si-a uitat parola:",
                    "SportMatch", JOptionPane.QUESTION_MESSAGE);
            if (who == null || who.trim().isEmpty()) return;
            try {
                String code = issueRecoveryCode(who.trim());
                JOptionPane.showMessageDialog(f,
                        "Cod nou pentru " + who.trim() + ":\n\n" + code
                                + "\n\nDa-i acest cod proprietarului contului.\n"
                                + "Il foloseste in aplicatie la \"Ai uitat parola?\".\n"
                                + "Codul vechi nu mai este valabil.",
                        "SportMatch", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(f, ex.getMessage(), "SportMatch", JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel center = new JPanel(new GridLayout(0, 1, 0, 10));
        center.add(info);
        center.add(recovery);

        JPanel p = new JPanel(new BorderLayout(12, 12));
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        p.add(center, BorderLayout.CENTER);
        p.add(stop, BorderLayout.SOUTH);

        f.setContentPane(p);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    /** Cod nou de recuperare pentru un cont existent, generat din fereastra serverului. */
    private static String issueRecoveryCode(String identifier) throws SQLException {
        int userId = scalarInt("SELECT id FROM users WHERE username = ? OR email = ?", identifier, identifier);
        if (userId == 0) throw new IllegalArgumentException("Nu exista un cont cu \"" + identifier + "\".");
        String code = recoveryCode();
        exec("UPDATE users SET recovery_hash = ? WHERE id = ?", hashPassword(code), userId);
        return code;
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
# SportMatch

SportMatch este o aplicație web pentru găsirea partenerilor de sport. Jucătorii își fac cont, își trec sporturile, zona și disponibilitatea, apoi pot posta anunțuri de meciuri, se pot înscrie la meciurile altora, pot vorbi între ei și pot urca în clasament pe baza rezultatelor.

Serverul rulează pe calculatorul tău și este scris într-un singur fișier Java, fără framework-uri. Interfața web, în limba română, este servită direct de server și merge din browser, inclusiv de pe telefon.

## Funcționalități

- **Conturi și autentificare.** Te înregistrezi cu utilizator, email, telefon și parolă. Parolele sunt salvate ca hash PBKDF2 (120.000 de iterații, cu salt), iar o sesiune ține 30 de zile. Dacă cineva își uită parola, administratorul îi generează un cod de recuperare din fereastra serverului.
- **Profil de jucător.** Ai avatar, descriere, zonă și disponibilitate. Pentru fiecare sport ai un punctaj separat.
- **Niveluri pe fiecare sport.** Nivelul depinde de punctaj: Începător de la 0 puncte, Intermediar de la 50 și Avansat de la 150.
- **Anunțuri.** Postezi sportul, locul, ora și numărul de locuri libere, iar ceilalți se pot înscrie. Feed-ul se poate filtra pe sport: fotbal, baschet, tenis, volei, padel, alergare, badminton.
- **Căutare de parteneri.** Jucătorii care practică același sport sunt ordonați după compatibilitate. Scorul crește dacă aveți aceeași zonă, un nivel apropiat și aceeași disponibilitate.
- **Înregistrarea meciurilor.** Introduci coechipierii, adversarii și rezultatul. Punctele câștigate sau pierdute țin cont de nivelul tău, de nivelul mediu al echipei tale și de cel al adversarilor. Înainte de meci poți vedea cât valorează acesta.
- **Clasament.** Arată topul jucătorilor pe fiecare sport și se poate filtra pe zonă.
- **Mesaje, aprecieri și notificări.**
- **Hartă și navigare.** Fiecare anunț are o hartă cu locația terenului. Traseul se calculează cu mașina sau pe jos, iar navigarea merge în timp real, cu indicații vocale în română. Traseul se recalculează la 25 de secunde sau când te abați cu peste 80 m de la drum. Din hartă poți deschide destinația și în Waze sau Google Maps.
- **Temă deschisă și temă închisă.**
- **Date demo.** La prima pornire se adaugă automat 12 jucători fictivi și 24 de anunțuri, ca aplicația să nu fie goală.

## Tehnologii

| Componentă | Ce se folosește |
|---|---|
| Server HTTP | `com.sun.net.httpserver.HttpServer` (inclus în JDK) |
| Bază de date | SQLite prin `sqlite-jdbc` |
| Securitate | PBKDF2 (`javax.crypto`), `SecureRandom` |
| Fereastra serverului | Swing |
| Front-end | HTML, CSS și JavaScript incluse în fișierul Java, plus Leaflet 1.9.4 pentru hartă |
| Acces din exterior | Cloudflare Tunnel (`cloudflared`) |

## Cerințe

- **JDK 17 sau mai nou.** Codul folosește `record`, text blocks și switch expressions.
- **[`sqlite-jdbc-3.45.1.0.jar`](https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.1.0/)** pus lângă `SportMatch.java`.
- **[`slf4j-api-1.7.32.jar`](https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.32/)** pus lângă `SportMatch.java`.
- **Conexiune la internet** pentru hartă, rute și căutarea adreselor.
- **`cloudflared`** este opțional. Îți trebuie doar dacă vrei acces de pe alte rețele sau GPS pe telefon.

Fișierele `.jar` nu sunt incluse în repository, pentru că sunt ignorate prin `.gitignore`.

## Pornire

### Windows

```bat
javac -encoding UTF-8 -cp "sqlite-jdbc-3.45.1.0.jar;slf4j-api-1.7.32.jar" SportMatch.java
java -cp ".;sqlite-jdbc-3.45.1.0.jar;slf4j-api-1.7.32.jar" SportMatch
```

### Linux / macOS

Pe aceste sisteme, căile din classpath se separă cu `:` în loc de `;`:

```bash
javac -encoding UTF-8 -cp "sqlite-jdbc-3.45.1.0.jar:slf4j-api-1.7.32.jar" SportMatch.java
java -cp ".:sqlite-jdbc-3.45.1.0.jar:slf4j-api-1.7.32.jar" SportMatch
```

După pornire se deschide fereastra **„SportMatch - server”**. Aceasta arată:

- adresa pentru acest calculator: `http://localhost:8080`;
- adresa pentru alte dispozitive din aceeași rețea Wi-Fi: `http://<ip-local>:8080`;
- calea către baza de date;
- butonul **„Cod de recuperare pentru un cont”**;
- butonul **„Oprește serverul”**.

### Acces din exterior, cu HTTPS

```bat
.\cloudflared.exe tunnel --url http://localhost:8080
```

Comanda afișează o adresă publică de forma `https://….trycloudflare.com`, pe care o poți trimite și altora.

Pentru butoanele **„Locația mea”** și **„Navighează spre teren”** e nevoie de această adresă. Browserele dau acces la GPS doar pe `https` sau pe `localhost`. Pe `http://192.168.x.x` locația nu este disponibilă, așa că trebuie să scrii manual adresa de plecare.

## Configurare

La prima pornire se creează automat două fișiere lângă program:

| Fișier | Conținut |
|---|---|
| `sportmatch.cfg` | Setările serverului. Momentan conține doar portul: `port=8080`. |
| `sportmatch.db` | Baza de date SQLite cu toate datele aplicației. |

Dacă portul 8080 e ocupat de alt program, schimbă-l în `sportmatch.cfg` și repornește serverul. Ca să pornești cu datele de la zero, șterge `sportmatch.db`.

## Servicii externe pentru hartă

| Serviciu | La ce folosește |
|---|---|
| [Nominatim (OpenStreetMap)](https://nominatim.openstreetmap.org) | Transformă adresa în coordonate și invers |
| [OSRM](https://router.project-osrm.org) | Traseul cu mașina: distanță, durată, viraje |
| [Valhalla](https://valhalla1.openstreetmap.de) | Traseul pe jos, cu indicații în română |
| [OpenStreetMap tiles](https://tile.openstreetmap.org) | Imaginile hărții |
| Leaflet 1.9.4 (unpkg) | Afișarea hărții în pagină |

Pentru mersul pe jos se folosește Valhalla, pentru că serverul public OSRM răspunde la fel pentru orice profil și ar întoarce trasee de mașină.

Răspunsurile se salvează în tabelele `geo_cache` și `route_cache`, ca să nu fie cerute de mai multe ori.

## API

Toate rutele întorc JSON. Rutele care necesită autentificare primesc token-ul de sesiune în header-ul `X-Token`.

| Metodă | Rută | Descriere |
|---|---|---|
| POST | `/api/register` | Creare cont |
| POST | `/api/login` | Autentificare |
| POST | `/api/recover` | Resetare parolă cu cod de recuperare |
| POST | `/api/logout` | Delogare |
| GET | `/api/me` | Datele utilizatorului curent |
| GET | `/api/players` | Lista jucătorilor |
| GET / POST | `/api/profile` | Vezi sau editezi un profil |
| POST | `/api/sports` | Adaugă un sport în profil |
| GET | `/api/search?sport=` | Parteneri compatibili |
| POST | `/api/like` | Apreciere |
| GET | `/api/feed?sport=` | Anunțurile |
| POST | `/api/post` | Anunț nou |
| POST | `/api/join` | Înscriere la un anunț |
| GET | `/api/matches` | Istoricul meciurilor |
| POST | `/api/match` | Înregistrare meci |
| GET | `/api/preview?sport=&teammates=&opponents=` | Cât valorează un meci |
| GET | `/api/ranking?sport=` | Clasament |
| GET | `/api/chats` | Conversații |
| GET | `/api/messages` | Mesajele dintr-o conversație |
| POST | `/api/message` | Trimite un mesaj |
| GET / POST | `/api/notifications` | Vezi notificările sau le marchezi ca citite |
| GET | `/api/map?post=&mode=` | Locația și traseul pentru un anunț |
| GET | `/api/nav?post=&mode=&lat=&lon=` | Pașii de navigare de la poziția curentă |
| GET | `/api/geocode?q=` sau `?lat=&lon=` | Adresă → coordonate sau coordonate → adresă |
| POST | `/api/start` | Setează locul de plecare |

Parametrul `mode` poate fi `masina` (valoarea implicită) sau `jos`.

## Structura proiectului

```
Proiect-p3/
├── SportMatch.java                          # serverul, logica, baza de date și interfața web
├── cloudflared.exe                          # Cloudflare Tunnel pentru Windows
├── instructiuni de pornire aplicatie.txt    # instrucțiunile de pornire inițiale
└── .gitignore
```

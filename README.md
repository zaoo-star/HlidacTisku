# 🖨 Hlídač tisku

Android aplikace a widget, který ukazuje **průběh 3D tisku** přímo na ploše telefonu – zbývající čas, procenta, kdy bude hotovo, náhled modelu, kameru a upozornění, když tisk skončí nebo se něco pokazí.

**Funguje s tiskárnami:**

| Značka | Připojení | Kamera | Historie | Ovládání |
|---|---|---|---|---|
| **Bambu Lab** (P1S, P1P, A1, A1 mini, X1C, H2D…) | Bambu účet (i mimo domov) nebo domácí síť | P1/A1 v domácí síti* | ✅ (přes účet) | světlo ✅, pauza/stop* |
| **Prusa** (MK4, MK4S, MK3.9, Mini, XL, Core One) | PrusaLink v domácí síti | – | – | pauza, pokračovat, stop |
| **Klipper** (Creality K1/K2, Elegoo Neptune 4, Sovol, Voron…) | Moonraker v domácí síti | ✅ | ✅ | pauza, pokračovat, stop |

\* Bambu některé funkce cizím aplikacím povolí jen se zapnutým LAN / Developer módem na tiskárně.

## ⬇️ Stažení

**[Stáhnout nejnovější verzi (APK)](https://github.com/zaoo-star/HlidacTisku/releases/latest/download/HlidacTisku.apk)**

1. Otevři odkaz v telefonu a stáhni soubor.
2. Otevři ho – Android se zeptá, jestli povolit instalaci z neznámého zdroje → **Povolit**.
3. Spusť **Hlídač tisku** → **Přidat tiskárnu** a postupuj podle průvodce.

Nové verze se instalují přes tu starou, nastavení zůstane.

## ✨ Co umí

- **Víc tiskáren najednou** – každá má svou kartu v přehledu.
- **Widgety na plochu** – každý widget může ukazovat jinou tiskárnu.
  - tvary: futuristický panel/kruh, obdélník, pruh, čtverec, kruh (nebo automaticky podle velikosti)
  - barvy: Neon, Zelená, Synthwave, Retro jantar, Matrix, Láva, Minimal
  - sám se přizpůsobí velikosti – písmo i počet řádků
  - klepnutí obnoví data, ikonka 📷 otevře kameru
- **Upozornění** – tisk hotový, pozastavený, chyba (u Bambu i s kódem chyby). Volitelně **hlasitý budík**, který zvoní i v tichém režimu.
- **Náhled modelu**, který se právě tiskne.
- **AMS** (Bambu) – barvy, typ a zbývající filament ve slotech.
- **Historie tisků a nacenění** – součty za měsíc, gramy, hodiny a odhad ceny podle tvých cen za kg filamentu a hodinu tisku.
- Obnovení dat po odemčení telefonu, odpočet času i mezi aktualizacemi.

## 🔋 Aby to běželo spolehlivě

Aplikace drží spojení s tiskárnou na pozadí (v liště je trvalá notifikace „Hlídám tiskárnu“).
- V aplikaci klepni na **Povolit běh na pozadí**.
- **Xiaomi / Redmi / POCO:** Informace o aplikaci → zapni **Autostart** a u Úspory baterie zvol **Bez omezení**.

## 🔒 Soukromí

Přihlašovací údaje a klíče zůstávají jen v telefonu. Aplikace komunikuje přímo s tiskárnou v domácí síti, u Bambu účtu se servery Bambu Lab. Žádná data neposílá nikam jinam.

## 🛠 Pro vývojáře

Kotlin, bez Android Studia – APK se sestavuje automaticky přes GitHub Actions po každém pushi do `main`.

- `Conns.kt` – připojení (Bambu MQTT, PrusaLink, Moonraker)
- `MonitorService.kt` – služba na pozadí a notifikace
- `PrintWidget.kt`, `Neon.kt` – widgety
- `*Activity.kt` – obrazovky aplikace

Neoficiální aplikace, není spojená s Bambu Lab, Prusa Research ani Creality. Písma Orbitron a Rajdhani jsou pod licencí SIL Open Font License.

---
title: "Kaskadenmodul KM-4"
---

### 📊 Allgemeine Daten
* **Geräte**: Steuert bis zu 4 Kessel der NK-Serie als Kaskade.
* **Kommunikation**: Zweidraht-Bus zu den Kesseln, Ethernet zur Gebäudeleittechnik NordControl.
* **Versorgung**: 230 V, Leistungsaufnahme höchstens 6 W.
* **Schutzart**: IP 20, nur für den Einbau im Schaltschrank.
* **Umgebungstemperatur**: 0 °C bis +50 °C im Betrieb.
* **Maße (B x H x D)**: ca. 90 mm x 110 mm x 60 mm, Hutschiene.

---

### 🔍 Betriebsarten
* **Folgeschaltung**: Das führende Gerät läuft zuerst, weitere Geräte schalten bei steigendem Bedarf zu.
* **Laufzeitausgleich**: Nach jeweils 100 Betriebsstunden wechselt das führende Gerät.
* **Notbetrieb**: Fällt der Bus aus, regelt jeder Kessel selbständig auf seine eingestellte Vorlauftemperatur.

---

### ⚙️ Diagnose
* **LED BUS**: Grün bei laufender Kommunikation, rot blinkend bei einem fehlenden Gerät.
* **LED RUN**: Grün im Betrieb, aus im Notbetrieb.
* Störungen der Kessel werden mit ihrem Fehlercode an die Leittechnik weitergegeben.

## Konfiguration

{{< include "../_konfiguration/KM-4.md" >}}

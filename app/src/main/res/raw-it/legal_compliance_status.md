# Stato di conformità

Ultima revisione: 2026-05-07

Legenda stati:
- `DONE`: implementato e verificato.
- `PENDING`: richiede verifiche prima/dopo il rilascio.
- `BLOCKER`: non distribuire una risorsa inclusa senza risolvere il problema.

## Lista di verifica attuale

1. Voce legale accessibile dalle impostazioni dell'orologio
   - Stato: `DONE`
   - Prova: schermata `Settings -> Licenses` disponibile nell'app.

2. Pagina crediti comprensibile
   - Stato: `DONE`
   - Prova: `licenses/CREDITS_AND_THANKS.md`.

3. Avvisi open source per le librerie principali
   - Stato: `DONE`
   - Prova: `licenses/THIRD_PARTY_NOTICES.md`.

4. Attribuzione dati e risorse
   - Stato: `DONE`
   - Prova: `licenses/DATA_AND_ASSET_ATTRIBUTION.md`.

5. Pagina condizioni servizi/API
   - Stato: `DONE`
   - Prova: `licenses/SERVICE_TERMS_AND_API_USAGE.md`.

6. Trasparenza fonti esterne dell'app companion
   - Stato: `DONE`
   - Prova: `licenses/COMPANION_EXTERNAL_SOURCES.md`.

7. Permesso di ridistribuzione per OpenAndroMaps/Elevate inclusi
   - Stato: `DONE`
   - Prova:
     - Il responsabile del progetto ha confermato il 2026-04-20 che OpenAndroMaps e Tobias Kuehn hanno acconsentito all'uso/integrazione in GlanceMap.
   - Note:
     - Conservare i messaggi originali dell'accordo fuori dal repository pubblico.
     - Prima di cambiare visibilità, registrare date di richiesta/risposta e ambito approvato in note di rilascio private o in una segnalazione interna.
     - L'ambito dovrebbe coprire ridistribuzione pubblica dei sorgenti, inclusione in APK/AAB e temi/risorse Elevate modificati o adattati usati da GlanceMap.

8. File licenza risorse referenziato da Elevate (`read_me_elevate.txt`)
   - Stato: `DONE`
   - Prova:
     - `app/src/main/assets/read_me_elevate.txt` è versionato accanto al sorgente di riserva nella radice `Elevate.xml`.
   - Note:
     - Referenziato da `app/src/main/assets/Elevate.xml`.
     - Le copie Elevate versionate includono `read_me_elevate.txt`; le compilazioni normali includono queste risorse locali senza scaricare temi.
     - Il sorgente di riserva versionato conserva le informazioni originali sulle licenze necessarie alla revisione pubblica dei sorgenti.

9. Limiti d'uso dell'istanza pubblica Overpass
   - Stato: `PENDING`
   - Note:
     - La politica pubblica principale indica limiti prudenti (10000 richieste/giorno, <1 GB/giorno).
     - Mantenere prudente la limitazione di richieste e importazioni.

10. Verifica licenza/ridistribuzione del tema OpenHiking incluso
   - Stato: `DONE`
   - Prova:
     - Il responsabile del progetto ha confermato il 2026-04-26 l'approvazione di OpenHiking.
     - Il responsabile del progetto ha confermato che la licenza del tema OpenHiking è `CC BY-SA`.
   - Note:
     - La copia OpenHiking è ora inclusa in `app/src/main/assets/theme/openhiking`.
     - L'integrazione ZIP del tema OpenHiking resta disponibile come aggiornamento opzionale nella compilazione.
     - Lo ZIP esaminato non includeva un evidente file licenza/readme separato.
     - Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
     - Archiviare privatamente pagina/versione originale esatta e dettagli dell'approvazione, quando disponibili.

11. Verifica licenza/ridistribuzione del tema French Kiss incluso
   - Stato: `DONE`
   - Prova:
     - La copia French Kiss è ora inclusa in `app/src/main/assets/theme/frenchkiss`.
     - La copia attuale è stata estratta dall'APK beta XCTrack fornito dall'utente.
     - L'XML incluso lo identifica come tema IGN TOP25 per XCTrack di Pascal Cochet.
     - Il responsabile del progetto ha confermato il 2026-04-27 l'approvazione dello sviluppatore French Kiss.
   - Note:
     - Non è stato osservato un file licenza separato accanto alle risorse del tema.
     - Conservare l'attribuzione attuale nei crediti, salvo richiesta successiva più specifica dell'autore originale.
     - Conservare il messaggio originale di approvazione fuori dal repository pubblico e archiviare privatamente date e ambito approvato.

12. Revisione licenza non commerciale del tema Tiramisu incluso
   - Stato: `DONE`
   - Prova:
     - La copia Tiramisu è inclusa in `app/src/main/assets/theme/tiramisu`.
     - L'archivio di rilascio esaminato include una licenza CC BY-NC-SA 3.0.
     - Il responsabile del progetto ha confermato il 2026-04-26 che il modello di distribuzione di GlanceMap non è commerciale.
   - Note:
     - Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
     - Rivedere prima di qualsiasi passaggio a distribuzione commerciale.

13. Revisione licenza non commerciale del tema Hike, Ride & Sight incluso
   - Stato: `DONE`
   - Prova:
     - La copia Hike, Ride & Sight è inclusa in `app/src/main/assets/theme/hike-ride-sight`.
     - L'intestazione XML esaminata dichiara CC BY-NC-SA 3.0.
     - Il responsabile del progetto ha confermato il 2026-04-26 l'approvazione all'uso/ridistribuzione da parte di GlanceMap.
     - I collegamenti ai crediti richiesti sono inclusi in `licenses/CREDITS_AND_THANKS.md`.
   - Note:
     - Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
     - Rivedere prima di qualsiasi passaggio a distribuzione commerciale.

14. Revisione licenza non commerciale del tema Voluntary incluso
   - Stato: `DONE`
   - Prova:
     - La copia Voluntary è inclusa in `app/src/main/assets/theme/voluntary`.
     - L'intestazione XML e la pagina crediti originale esaminate dichiarano CC BY-NC-SA 3.0.
     - Il responsabile del progetto ha confermato il 2026-04-29 l'approvazione dell'autore all'integrazione in GlanceMap.
     - Legenda e collegamenti di riferimento sono inclusi nell'app companion.
   - Note:
     - Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
     - Conservare il messaggio originale di approvazione fuori dal repository pubblico e archiviare privatamente date e ambito approvato.
     - Rivedere prima di qualsiasi passaggio a distribuzione commerciale.

15. Revisione licenza non commerciale del tema OS Map incluso
   - Stato: `DONE`
   - Prova:
     - Le copie giorno/notte di OS Map V4 sono incluse in `app/src/main/assets/theme/os-map`.
     - L'intestazione XML esaminata dichiara CC BY-NC-SA 3.0.
     - Legenda e collegamenti alla discussione sono inclusi nell'app companion.
   - Note:
     - Conservare attribuzione e obblighi di condivisione allo stesso modo nella documentazione del progetto.
     - Rivedere prima di qualsiasi passaggio a distribuzione commerciale.

## Criteri per repository pubblico e rilascio

Per un repository o rilascio pubblico dell'app, risolvere tutti gli elementi `BLOCKER`.
Considerare bloccanti gli elementi `PENDING` irrisolti su licenza/ridistribuzione delle risorse incluse
salvo rimuovere le risorse interessate dal ramo pubblico o
accettare esplicitamente il rischio residuo. Pubblicare questo repository
pubblica anche le risorse di terzi incluse.

Esito attuale:
- `READY FROM A BUNDLED-ASSET LICENSING STANDPOINT`, senza blocchi di ridistribuzione registrati ancora aperti per i temi attuali.
- Permesso OpenAndroMaps/Elevate e prove readme/licenza non sono più blocchi, in base alla conferma del responsabile e al readme sorgente di riserva versionato.
- OpenHiking, French Kiss, Tiramisu, Hike, Ride & Sight, Voluntary e OS Map non sono più blocchi, in base alle conferme registrate e all'attuale modello non commerciale ove pertinente.
- Le attività residue prima della pubblicazione sono operative e descritte in `docs/PUBLIC_REPO_CHECKLIST.md`, come metadati GitHub, attivazione Pages e segnalazioni di sicurezza.

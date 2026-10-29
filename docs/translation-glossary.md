# Translation glossary

These terms are derived from the approved German, Spanish, French, Italian and
Polish resources. No existing translation was rewritten. Each row cites its
resource evidence; nouns extracted from longer phrases are given in their normal
dictionary form. Inflect them naturally rather than copying a noun into every
grammatical context.

The CSV files are ready for the corresponding Weblate glossary-language upload:
[German](translation-glossary/de.csv), [Spanish](translation-glossary/es.csv),
[French](translation-glossary/fr.csv), [Italian](translation-glossary/it.csv),
[Polish](translation-glossary/pl.csv). They use Weblate's documented
`source,target,developer_comments` CSV fields and UTF-8. See
[glossary import](weblate-setup.md#glossary-import) for the activation procedure.

Unsupported languages intentionally have no invented target terms. Add reviewed
terms when their translators establish the wording.

| Concept / source wording | German | Spanish | French | Italian | Polish | Evidence resource |
|---|---|---|---|---|---|---|
| Map | Karte | Mapa | Carte | Mappa | Mapa | `download_map` |
| Route | Route | Ruta | Itinéraire | Percorso | Trasa | `gpx_route` |
| Routing | Routenberechnung | Cálculo de rutas | Itinéraires | Calcolo percorsi | Wyznaczanie tras | `download_routing` |
| Navigation (Navigate menu) — Navigate | Navigieren | Navegar | Naviguer | Naviga | Nawigacja | `navigation_navigate` |
| Recording | Aufzeichnung | Grabación | Enregistrement | Registrazione | Rejestracja | `common_recording` |
| Elevation | Höhe | Altitud | Altitude | Quota | Wysokość | `common_elevation` |
| Terrain (Standard terrain) — Standard terrain | Standardgelände | Relieve estándar | Relief standard | Terreno standard | Teren standardowy | `dem_standard_terrain` |
| Download | Herunterladen | Descargar | Télécharger | Scarica | Pobierz | `common_download` |
| Refresh | Aktualisieren | Actualizar | Actualiser | Aggiorna | Odśwież | `download_refresh` |
| Settings | Einstellungen | Ajustes | Paramètres | Impostazioni | Ustawienia | `common_settings` |
| GPS | GPS | GPS | GPS | GPS | GPS | `common_gps` |
| POI | POI | POI | POI | POI | POI | `common_poi` |
| GPX | GPX | GPX | GPX | GPX | GPX | `common_gpx` |
| Watch | Uhr | Reloj | Montre | Orologio | Zegarek | `settings_watch` |
| Shelter | Unterstand | Refugio | Abri | Riparo | Schronienie | `poi_shelter` |
| Landmark — Landmarks | Sehenswürdigkeiten | Lugares destacados | Repères | Luoghi notevoli | Punkty orientacyjne | `poi_landmarks` |
| Sports/Leisure | Sport/Freizeit | Deporte/Ocio | Sports/Loisirs | Sport/Tempo libero | Sport/Rekreacja | `poi_sports_leisure` |
| Pause | Pause | Pausa | Pause | Pausa | Pauza | `common_pause` |
| Resume | Fortsetzen | Reanudar | Reprendre | Riprendi | Wznów | `common_resume` |
| Retry | Wiederholen | Reintentar | Réessayer | Riprova | Ponów | `common_retry` |
| Cancel | Abbrechen | Cancelar | Annuler | Annulla | Anuluj | `common_cancel` |
| Close | Schließen | Cerrar | Fermer | Chiudi | Zamknij | `common_close` |
| Save | Speichern | Guardar | Enregistrer | Salva | Zapisz | `common_save` |
| Start | Starten | Iniciar | Démarrer | Avvia | Start | `common_start` |
| Track | Track | traza | trace | traccia | ślad | `settings_track_color` |
| Compass | Kompass | brújula | Boussole | Bussola | Kompas | `common_compass_not_available_on_this_watch` |
| Cache | Cache | caché | cache | cache | pamięć podręczna | `settings_clear_cache` |
| Transfer | Übertragung | Transferencia | Transfert | Trasferimento | Transfer | `download_transfer_paused_for_more_than_s` |
| Phone | Telefon | teléfono | téléphone | telefono | telefon | `gpx_no_phone_connected` |

## Context matters

- **Route / routing / navigation / track:** a planned route, calculating it,
  following it, and a track's geometry are different concepts. French routing is
  currently labelled “Itinéraires”; its route noun is “Itinéraire”. The Navigate
  row records the existing menu wording, including verbs, not a forced noun for
  every screen.
- **Recording / save / track:** recording is an ongoing activity. Save is an
  action. A saved recording and its track may legitimately use different nouns.
- **Elevation / terrain:** the elevation row follows the common UI metric. Other
  altitude labels may use a context-specific term; Italian, for example, also
  uses “Altitudine”. Terrain is illustrated by the approved Standard terrain
  phrase rather than an invented standalone translation.
- **Shelter / hut / landmark:** Shelter is generic cover. An alpine hut or staffed
  refuge is not always the same thing. Existing hut labels include Hütte, Cabaña,
  Refuge, Rifugio and Schronisko. Landmark categories are broader than historic
  monuments; do not silently narrow them to “monuments”.
- **Watch / phone / transfer:** use device nouns, not the verbs “to watch” or
  “to phone”. The evidence may contain an inflected form or compound.
- **Cache:** this is disposable temporary data, not the downloaded maps or GPX
  originals. Preserve that distinction in destructive-action descriptions.
- **Names and abbreviations:** keep GlanceMap, OpenStreetMap, Mapsforge, BRouter,
  Refuges.info, GPS, GPX, POI, DEM, REC and TBT unchanged where intentional.
  Weblate can mark these glossary terms untranslatable. Do not force an English
  technical abbreviation into prose where a clear existing local term is used.

Shorter wording wins only when it is equally accurate, natural and clear. Treat
this glossary as context, not permission to normalize the existing translations.

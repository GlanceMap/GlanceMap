# Źródła danych i zasobów

Ostatni przegląd: 2026-05-07

Ten plik zawiera informacje o autorstwie i licencjach zasobów map innych niż kod, zbiorów danych i importowanych źródeł POI używanych przez GlanceMap.

## 1) Dane OpenStreetMap

- Zalecana treść informacji o autorstwie:
  - `Map data (c) OpenStreetMap contributors`
- Źródła:
  - https://www.openstreetmap.org/copyright
  - https://opendatacommons.org/licenses/odbl/1-0/

## 2) Motyw mapy Elevate (ekosystem OpenAndroMaps)

- Lokalny plik motywu:
- `app/src/main/assets/Elevate.xml`
- `app/src/main/assets/theme/elevate/`
- `app/src/main/assets/theme/elevate-winter/`
- `app/src/main/assets/theme/elevate-winter-white/`
- Deklaracja licencji zawarta w pliku:
  - CC BY-NC-SA 4.0 dla stylu mapy.
  - Informacja o użyciu komercyjnym odsyła do BY-ND 4.0.
- Źródła oryginalne:
  - https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme
  - https://www.openandromaps.org/en/license

Ważne:
- `Elevate.xml` wskazuje `read_me_elevate.txt` jako źródło licencji ponownego użycia zasobów.
- Wersje motywów w repozytorium zawierają `read_me_elevate.txt`; zwykłe kompilacje nie pobierają zasobów motywów.
- Główny zapasowy plik źródłowy przechowuje `app/src/main/assets/read_me_elevate.txt` obok `Elevate.xml`.
- Strona licencji OpenAndroMaps zawiera ograniczenia dotyczące dołączania i zezwoleń.
- Właściciel projektu poinformował o otrzymaniu zgody OpenAndroMaps i Tobiasa Kuehna na użycie/integrację OpenAndroMaps/Elevate w GlanceMap.
- Dokładne daty zgody i zatwierdzony zakres należy zapisać poza publicznym repozytorium przed jego upublicznieniem.

## 3) Motyw Mapsforge OpenHiking

- Lokalne dołączone pliki motywu:
  - `app/src/main/assets/theme/openhiking/OpenHiking.xml`
  - Drzewo zasobów `app/src/main/assets/theme/openhiking/`
- Przypięta wersja źródłowa w konfiguracji projektu:
  - `2026-02-10`
- Opcjonalne archiwum odświeżania podczas kompilacji:
  - `https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus`
- Zawartość wyodrębnionego motywu sprawdzona 2026-03-14:
  - `OpenHiking/OpenHiking.xml`
  - Podkatalogi SVG/wzorów/symboli dołączone do ZIP.
- Źródła oryginalne:
  - https://openhiking.eu/en/downloads/mapsforge-maps
  - https://openhiking.eu/locus/Hungary.xml

Ważne:
- Pobrany ZIP nie zawierał jednoznacznego, osobnego pliku licencji/readme.
- Właściciel projektu potwierdził 2026-04-26 otrzymanie zgody od OpenHiking.
- Właściciel projektu potwierdził, że licencja motywu OpenHiking to `CC BY-SA`.
- Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
- Gdy będą dostępne, zarchiwizuj prywatnie dokładną stronę/wersję źródłową i szczegóły zgody.
- Informacja dla użytkowników jest dostępna w `licenses/OPENHIKING_THEME.md`.

## 4) Motyw Mapsforge Tiramisu

- Lokalne dołączone pliki motywu:
  - `app/src/main/assets/theme/tiramisu/Tiramisu.xml`
  - Drzewo zasobów `app/src/main/assets/theme/tiramisu/res/`
- Źródło dołączonej wersji:
  - https://github.com/IgorMagellan/Tiramisu/releases/tag/v4.4
- Zawartość wydania sprawdzona 2026-03-18:
  - `Tiramisu.xml`
  - Drzewo zasobów SVG `res/`
  - `LICENSE`
  - `README.md`
- Licencja zadeklarowana w oryginalnym pakiecie:
  - CC BY-NC-SA 3.0

Ważne:
- Oryginalna licencja jest niekomercyjna.
- Właściciel projektu potwierdził 2026-04-26, że model dystrybucji GlanceMap jest niekomercyjny, więc obecny plan publicznego repozytorium/aplikacji jest uznawany za zgodny.
- Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
- Przeprowadź ponowny przegląd, jeśli model dystrybucji stanie się komercyjny.
- Informacja dla użytkowników jest dostępna w `licenses/TIRAMISU_THEME.md`.

## 5) Motyw Mapsforge French Kiss

- Lokalne dołączone pliki motywu:
  - `app/src/main/assets/theme/frenchkiss/frenchkiss.xml`
  - `app/src/main/assets/theme/frenchkiss/motifs/`
- Źródło wersji użytej w obecnym lokalnym pakiecie:
  - `xctrack-beta-latest.apk` dostarczony przez użytkownika
- Zawartość wersji sprawdzona 2026-03-18:
  - `frenchkiss.xml`
  - Drzewo zasobów PNG `motifs/`
- Informacja o pochodzeniu zawarta w nagłówku XML:
  - `IGN TOP25 theme for XCTrack by Pascal Cochet`

Ważne:
- Przy wyodrębnionych zasobach motywu nie znaleziono osobnego pliku licencji.
- Właściciel projektu potwierdził 2026-04-27 otrzymanie zgody od autora French Kiss.
- Zachowaj obecne informacje o autorstwie w podziękowaniach projektu, chyba że autor później poprosi o bardziej szczegółowe brzmienie.
- Zarchiwizuj prywatnie dokładne szczegóły zgody.
- Informacja dla użytkowników jest dostępna w `licenses/FRENCH_KISS_THEME.md`.

## 6) Motyw Mapsforge Hike, Ride & Sight

- Lokalne dołączone pliki motywu:
  - `app/src/main/assets/theme/hike-ride-sight/HikeRideSight.xml`
  - `app/src/main/assets/theme/hike-ride-sight/PATTERNS/`
  - `app/src/main/assets/theme/hike-ride-sight/SYMBOLS/`
- Strona motywu:
  - http://j.seydoux.free.fr/locus/hrs.html
- Ręczne pobieranie ZIP:
  - http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.zip
- Sprawdzona oryginalna deklaracja licencji:
  - Deklarowana w nagłówku komentarza XML jako CC BY-NC-SA 3.0

Ważne:
- W pobranym ZIP nie znaleziono osobnego pliku licencji.
- Podana licencja jest niekomercyjna.
- Właściciel projektu potwierdził 2026-04-26 otrzymanie zgody na użycie/rozpowszechnianie przez GlanceMap.
- Obecny niekomercyjny model dystrybucji GlanceMap jest uznawany za zgodny.
- Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
- Wymagane odnośniki dla użytkowników znajdują się w `licenses/CREDITS_AND_THANKS.md`.
- Informacja dla użytkowników jest dostępna w `licenses/HIKE_RIDE_SIGHT_THEME.md`.

## 7) Motyw Mapsforge Voluntary

- Lokalne dołączone pliki motywu:
  - `app/src/main/assets/theme/voluntary/Voluntary V5.xml`
  - `app/src/main/assets/theme/voluntary/vol_res/`
- Strona motywu:
  - https://voluntary.nichesite.org/
- Ręczne pobieranie ZIP:
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20MF5.zip
- Legenda:
  - https://voluntary.nichesite.org/key.html
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20Key.pdf
- Sprawdzona oryginalna deklaracja licencji:
  - Deklarowana w nagłówku komentarza XML i oryginalnej stronie podziękowań jako CC BY-NC-SA 3.0

Ważne:
- W pobranym ZIP nie znaleziono osobnego pliku licencji.
- Podana licencja jest niekomercyjna.
- Właściciel projektu potwierdził 2026-04-29 otrzymanie zgody twórcy na integrację z GlanceMap.
- Obecny niekomercyjny model dystrybucji GlanceMap jest uznawany za zgodny.
- Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
- Informacja dla użytkowników jest dostępna w `licenses/VOLUNTARY_THEME.md`.

## 8) Motyw Mapsforge OS Map

- Lokalne dołączone pliki motywu:
  - `app/src/main/assets/theme/os-map/OS Map V4 Day.xml`
  - `app/src/main/assets/theme/os-map/OS Map V4 Night.xml`
  - `app/src/main/assets/theme/os-map/os_res/`
- Dyskusja o motywie:
  - https://forum.locusmap.eu/index.php?topic=7000.msg59948#msg59948
- ZIP wydania:
  - https://drive.google.com/uc?export=download&id=1RzZTMLRIQM1pY_R8FXdVxKPSTqO2LHUE
- Legendy:
  - https://drive.google.com/file/d/1PE0eBzJnGMbDs9a_V_uhQQa0db5RK-Zs/view
  - https://drive.google.com/file/d/1OwAeuBtYN-XxjGkpOs3SrdYAUwYXDets/view
- Sprawdzona oryginalna deklaracja licencji:
  - Deklarowana w nagłówku komentarza XML jako CC BY-NC-SA 3.0

Ważne:
- Podana licencja jest niekomercyjna.
- Obecny niekomercyjny model dystrybucji GlanceMap jest uznawany za zgodny.
- Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
- Informacja dla użytkowników jest dostępna w `licenses/OS_MAP_THEME.md`.

## 9) Import danych Refuges.info

- Aplikacja towarzysząca importuje dane POI z:
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`
- Źródło informacji o licencji:
  - https://www.refuges.info/wiki/licence

Uwagi:
- Refuges.info podaje licencję CC BY-SA dla treści stron punktów.
- Refuges.info wskazuje też dane OpenStreetMap i ograniczenia ODbL tam, gdzie mają zastosowanie.

## 9) Uzupełnianie POI OSM przez Overpass API

- Punkt dostępu do uzupełniania w aplikacji towarzyszącej:
  - `https://overpass-api.de/api/interpreter`
- Dokumentacja:
  - https://wiki.openstreetmap.org/wiki/Overpass_API

Informacja o użyciu:
- Wytyczne instancji publicznej wskazują ostrożne limity (zapytań/dzień i transferu/dzień).

## 10) Dane wysokości DEM (renderowanie nachylenia/cieniowania)

- Procesy kompilacji i działania używają kafelków typu DEM3 `.hgt.zip` i opcjonalnych kafelków `.hgt.gz` o rozdzielczości 1 sekundy łuku.
- Domyślne źródło w aplikacji/procesie kompilacji:
  - `https://download.mapsforge.org/maps/dem/dem3`
- Opcjonalne źródło o rozdzielczości 1 sekundy łuku:
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`
- Odnośniki:
  - https://download.mapsforge.org/maps/dem/
  - https://download.mapsforge.org/maps/dem/ReadMe.md
  - https://www.mapzen.com/blog/terrain-tile-service/
  - https://www.mapzen.com/rights/

## 11) Zasoby ikon POI

- Lokalne ikony źródłowe POI pochodzą z zestawów SVG Maki i Temaki:
  - https://github.com/mapbox/maki
  - https://github.com/rapideditor/temaki
- Proces kompilacji może też pobierać zapasowe ikony SVG Maki z:
  - https://github.com/mapbox/maki
- Licencja:
  - CC0 1.0 Universal
  - https://creativecommons.org/publicdomain/zero/1.0/

## 12) Zasoby i segmenty tras BRouter

- Dołączone zasoby profili tras:
  - `app/src/main/assets/brouter/profiles2/lookups.dat`
  - `app/src/main/assets/brouter/profiles2/hiking-mountain.brf`
- Źródło pobierania segmentów tras w aplikacji towarzyszącej:
  - `https://brouter.de/brouter/segments4`
- Źródła oryginalne:
  - https://github.com/abrensch/brouter
  - https://brouter.de/brouter/

Uwagi:
- Projekt BRouter podaje, że segmenty tras `.rd5` są generowane z danych OpenStreetMap i aktualizowane co tydzień.
- Uznanie autorstwa OpenStreetMap pozostaje istotne dla danych segmentów tras.
- Aplikacja na zegarek osobno dołącza kod BRouter na licencji MIT; informacja o licencji kodu znajduje się w `THIRD_PARTY_NOTICES.md`.

## 13) Pliki importowane przez użytkownika

- Aplikacja obsługuje importowane przez użytkownika pliki map/POI/GPX.
- Licencje i prawa do rozpowszechniania plików użytkownika pozostają jego odpowiedzialnością.

## 14) Praktyczna lista kontrolna zgodności

- Utrzymuj ekran prawny dostępny z ustawień zegarka.
- Zachowaj przystępność i zwięzłość `CREDITS_AND_THANKS.md`.
- Utrzymuj zgodność szczegółowej dokumentacji prawnej z odnośnikami zewnętrznymi i API aplikacji towarzyszącej.
- Sprawdzaj ponownie warunki przy każdej zmianie adresów dostawców lub źródeł importu.

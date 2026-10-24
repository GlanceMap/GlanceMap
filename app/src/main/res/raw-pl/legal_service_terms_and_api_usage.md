# Warunki usług i użycie API

Ostatni przegląd: 2026-05-06

Ten dokument opisuje ograniczenia prawne i użytkowe usług zewnętrznych oraz dostawców wskazywanych przez aplikację/proces kompilacji, z wyłączeniem bibliotek.

## 1) OpenAndroMaps i Elevate

Zastosowanie:
- Polecane źródła map do pobrania w aplikacji towarzyszącej.
- Pobieranie motywu Elevate i lokalne zasoby zapasowe podczas kompilacji/działania aplikacji na zegarek.

Odnośniki:
- https://www.openandromaps.org/en/license
- https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme

Ważne warunki (z oryginalnej strony licencji):
- Bezpośrednie odnośniki do map są określone jako zabronione.
- Sprzedaż map lub dołączanie ich do aplikacji wymaga pisemnej zgody dostawcy map.
- Integracja motywów „andromaps_” jest zasadniczo dozwolona dla bezpłatnych usług aktualizacji.
- Integracja motywów Tobiasa (w tym Elevate) wymaga jego pisemnej zgody.

Stan projektu:
- Właściciel projektu poinformował o otrzymaniu zgody OpenAndroMaps i Tobiasa Kuehna na użycie/integrację OpenAndroMaps/Elevate w GlanceMap.
- Przechowuj oryginalne wiadomości ze zgodą poza publicznym repozytorium.
- Przed upublicznieniem GitHub zapisz daty prośby/odpowiedzi i zatwierdzony zakres w prywatnych uwagach do wydania lub zgłoszeniu wewnętrznym.

## 2) Motyw Mapsforge OpenHiking

Zastosowanie:
- Lokalne dołączone zasoby motywu OpenHiking w aplikacji na zegarek.
- Opcjonalne pobieranie odświeżenia OpenHiking w procesie kompilacji aplikacji na zegarek.
- Odnośnik do strony map/motywów OpenHiking w aplikacji towarzyszącej.

Odnośniki:
- https://openhiking.eu/en/downloads/mapsforge-maps
- https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus

Uwagi do warunków:
- Publiczna strona udostępnia bezpośrednie odnośniki pobierania/instalacji, ale nie znaleziono udokumentowanego API ogólnego przeznaczenia.
- ZIP motywu pobrany i sprawdzony 2026-03-14 nie zawierał jednoznacznego, osobnego pliku licencji/readme.

Stan projektu:
- Właściciel projektu potwierdził 2026-04-26 otrzymanie zgody od OpenHiking.
- Właściciel projektu potwierdził, że licencja motywu OpenHiking to `CC BY-SA`.
- Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
- Aktualne szczegóły dołączonej wersji zapisuj w `licenses/OPENHIKING_THEME.md`.

## 3) Motyw Mapsforge Voluntary

Zastosowanie:
- Lokalne dołączone zasoby motywu Voluntary w aplikacji na zegarek.
- Odnośniki do strony i legendy Voluntary w aplikacji towarzyszącej.

Odnośniki:
- https://voluntary.nichesite.org/
- https://voluntary.nichesite.org/downloads.html
- https://voluntary.nichesite.org/key.html
- https://voluntary.nichesite.org/credits.html

Uwagi do warunków:
- Publiczna strona udostępnia bezpośrednie odnośniki ręcznego pobierania i legendy, ale nie znaleziono udokumentowanego API ogólnego przeznaczenia.
- ZIP motywu pobrany i sprawdzony 2026-04-29 nie zawierał jednoznacznego, osobnego pliku licencji/readme.
- Nagłówek XML i strona podziękowań określają licencję motywu jako CC BY-NC-SA 3.0.

Stan projektu:
- Właściciel projektu potwierdził 2026-04-29 otrzymanie zgody twórcy na integrację z GlanceMap.
- Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
- Aktualne szczegóły dołączonej wersji zapisuj w `licenses/VOLUNTARY_THEME.md`.

## 4) API i dane Refuges.info

Zastosowanie:
- Wywołania API aplikacji towarzyszącej do:
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`

Odnośniki:
- Główny adres API: https://www.refuges.info/api
- Strona licencji: https://www.refuges.info/wiki/licence

Uwagi do warunków:
- Treści stron punktów Refuges.info są publikowane na licencji Creative Commons BY-SA.
- Refuges.info wykorzystuje też dane OpenStreetMap na warunkach ODbL tam, gdzie ma to zastosowanie.

## 5) OpenStreetMap i Overpass

Zastosowanie:
- Uznanie autorstwa OSM w aplikacji.
- Rastrowe kafelki tła wyboru mapy w aplikacji towarzyszącej:
  - `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
- Uzupełnianie POI OSM przez punkt dostępu Overpass API:
  - `https://overpass-api.de/api/interpreter`

Odnośniki:
- Prawa autorskie i uznanie autorstwa OSM: https://www.openstreetmap.org/copyright
- Zasady użycia kafelków OSM: https://operations.osmfoundation.org/policies/tiles/
- ODbL: https://opendatacommons.org/licenses/odbl/1-0/
- Dokumentacja Overpass API: https://wiki.openstreetmap.org/wiki/Overpass_API

Informacja o zasadach użycia:
- Kafelki OSM mogą służyć wyłącznie zwykłemu interaktywnemu przeglądaniu. Nie dodawaj pobierania wyprzedzającego, masowego ani buforowania offline kafelków z `tile.openstreetmap.org`.
- Kafelki OSM wymagają widocznego uznania autorstwa, oficjalnego adresu HTTPS, jednoznacznego User-Agent aplikacji i buforowania HTTP respektującego nagłówki pamięci podręcznej serwera.
- Wytyczne publicznej instancji Overpass wskazują ostrożne, bezpieczne użycie w granicach:
  - mniej niż 10 000 zapytań/dzień
  - mniej niż 1 GB pobierania/dzień

## 6) Silnik i segmenty tras BRouter

Zastosowanie:
- Obliczanie tras offline w aplikacji na zegarek przez dołączone moduły BRouter.
- Pobieranie plików segmentów tras `.rd5` przez aplikację towarzyszącą z:
  - `https://brouter.de/brouter/segments4`

Odnośniki:
- Projekt: https://github.com/abrensch/brouter
- Oficjalna strona: https://brouter.de/brouter/
- Katalog segmentów: https://brouter.de/brouter/segments4/

Uwagi do warunków i uznania autorstwa:
- Projekt źródłowy podaje, że BRouter jest wydawany na licencji MIT.
- Projekt źródłowy podaje, że pliki segmentów `.rd5` są generowane z danych OpenStreetMap i aktualizowane co tydzień.
- Uznanie autorstwa OpenStreetMap i warunki ODbL pozostają więc istotne dla danych tras BRouter.

Uwaga projektu:
- Na sprawdzonych 2026-03-20 oryginalnych stronach BRouter nie znaleziono osobnej publicznej strony zasad usługi ani limitów żądań.
- Wniosek projektu: zachowaj umiarkowane pobieranie w aplikacji towarzyszącej i ponownie sprawdź oryginalne warunki przed automatycznym użyciem na dużą skalę.

## 7) Źródła DEM i terenu

Zastosowanie:
- Pobieranie DEM3 z:
  - `https://download.mapsforge.org/maps/dem/dem3`
- Opcjonalne pobieranie DEM o rozdzielczości 1 sekundy łuku z:
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`

Odnośniki:
- https://download.mapsforge.org/maps/dem/
- https://download.mapsforge.org/maps/dem/ReadMe.md
- https://www.mapzen.com/blog/terrain-tile-service/
- https://www.mapzen.com/rights/

## 8) Źródło ikon POI podczas kompilacji

Zastosowanie:
- Pobieranie ikon SVG Maki podczas kompilacji.

Odnośniki:
- https://github.com/mapbox/maki
- Licencja: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/)

## 9) Kontekst warunków usług Google Play

Zastosowanie:
- Zależności czasu działania `play-services-wearable`, `play-services-location`.

Odnośniki:
- Umowa licencyjna Android SDK: https://developer.android.com/studio/terms
- Wytyczne informacji OSS usług Google Play: https://developers.google.com/android/guides/opensource

Uwaga:
- To warunki SDK/usług Google, a nie standardowe licencje OSS, takie jak Apache/LGPL.

## 10) Oświadczenie o braku powiązań

Aplikacja odwołuje się do zewnętrznych dostawców i stron dla wygody użytkownika. O ile dostawcy wyraźnie nie stwierdzą inaczej, GlanceMap nie jest z nimi oficjalnie powiązane ani przez nich wspierane.

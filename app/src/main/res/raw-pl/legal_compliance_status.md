# Stan zgodności

Ostatni przegląd: 2026-05-07

Legenda stanów:
- `DONE`: wdrożone i zweryfikowane.
- `PENDING`: wymaga dalszych działań przed wydaniem lub po nim.
- `BLOCKER`: nie rozpowszechniać dołączonego zasobu bez rozwiązania problemu.

## Aktualna lista kontrolna

1. Informacje prawne dostępne z ustawień zegarka
   - Stan: `DONE`
   - Dowód: ekran `Settings -> Licenses` dostępny w aplikacji.

2. Przystępna strona autorów i podziękowań
   - Stan: `DONE`
   - Dowód: `licenses/CREDITS_AND_THANKS.md`.

3. Informacje o otwartych licencjach głównych bibliotek
   - Stan: `DONE`
   - Dowód: `licenses/THIRD_PARTY_NOTICES.md`.

4. Źródła danych i zasobów
   - Stan: `DONE`
   - Dowód: `licenses/DATA_AND_ASSET_ATTRIBUTION.md`.

5. Strona warunków usług/API
   - Stan: `DONE`
   - Dowód: `licenses/SERVICE_TERMS_AND_API_USAGE.md`.

6. Przejrzystość źródeł zewnętrznych aplikacji towarzyszącej
   - Stan: `DONE`
   - Dowód: `licenses/COMPANION_EXTERNAL_SOURCES.md`.

7. Zgoda na rozpowszechnianie dołączonego OpenAndroMaps/Elevate
   - Stan: `DONE`
   - Dowód:
     - Właściciel projektu potwierdził 2026-04-20, że OpenAndroMaps i Tobias Kuehn zgodzili się na użycie/integrację w GlanceMap.
   - Uwagi:
     - Przechowuj oryginalne wiadomości ze zgodą poza publicznym repozytorium.
     - Przed upublicznieniem zapisz daty prośby i odpowiedzi oraz zatwierdzony zakres w prywatnych uwagach do wydania lub zgłoszeniu wewnętrznym.
     - Zakres powinien obejmować publiczne rozpowszechnianie źródeł, dołączanie do APK/AAB oraz wszelkie zmienione/dostosowane motywy i zasoby Elevate używane przez GlanceMap.

8. Plik licencji zasobów wskazany przez Elevate (`read_me_elevate.txt`)
   - Stan: `DONE`
   - Dowód:
     - `app/src/main/assets/read_me_elevate.txt` jest w repozytorium obok głównego zapasowego pliku źródłowego `Elevate.xml`.
   - Uwagi:
     - Wskazany w `app/src/main/assets/Elevate.xml`.
     - Wersje Elevate zapisane w repozytorium zawierają `read_me_elevate.txt`; zwykłe kompilacje pakują te lokalne zasoby bez pobierania motywów.
     - Zapisany zapasowy plik źródłowy zachowuje informacje o licencjach oryginału i zasobów wymagane przy publicznym przeglądzie źródeł.

9. Limity użycia publicznej instancji Overpass
   - Stan: `PENDING`
   - Uwagi:
     - Zasady głównej instancji publicznej wskazują ostrożne limity (10 000 zapytań/dzień, <1 GB/dzień).
     - Monitorowanie i ograniczanie tempa importu powinno pozostać zachowawcze.

10. Weryfikacja licencji i rozpowszechniania dołączonego motywu OpenHiking
   - Stan: `DONE`
   - Dowód:
     - Właściciel projektu potwierdził 2026-04-26 otrzymanie zgody od OpenHiking.
     - Właściciel projektu potwierdził, że licencja motywu OpenHiking to `CC BY-SA`.
   - Uwagi:
     - Wersja OpenHiking jest teraz dołączona w `app/src/main/assets/theme/openhiking`.
     - Integracja ZIP motywu OpenHiking pozostaje opcjonalną ścieżką odświeżania w procesie kompilacji.
     - Sprawdzone archiwum ZIP nie zawierało jednoznacznego, osobnego pliku licencji/readme.
     - Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
     - Gdy będą dostępne, zarchiwizuj prywatnie dokładną stronę/wersję źródłową i szczegóły zgody.

11. Weryfikacja licencji i rozpowszechniania dołączonego motywu French Kiss
   - Stan: `DONE`
   - Dowód:
     - Wersja French Kiss jest teraz dołączona w `app/src/main/assets/theme/frenchkiss`.
     - Bieżącą wersję wyodrębniono z dostarczonego przez użytkownika APK XCTrack beta.
     - Dołączony XML opisuje ją jako motyw IGN TOP25 dla XCTrack autorstwa Pascala Cocheta.
     - Właściciel projektu potwierdził 2026-04-27 otrzymanie zgody od autora French Kiss.
   - Uwagi:
     - Przy zasobach motywu nie znaleziono osobnego pliku licencji.
     - Zachowaj obecne informacje o autorstwie w podziękowaniach projektu, chyba że autor później poprosi o bardziej szczegółowe brzmienie.
     - Przechowuj oryginalną zgodę poza publicznym repozytorium i zarchiwizuj prywatnie daty prośby/odpowiedzi oraz zatwierdzony zakres.

12. Przegląd niekomercyjnej licencji dołączonego motywu Tiramisu
   - Stan: `DONE`
   - Dowód:
     - Wersja Tiramisu jest dołączona w `app/src/main/assets/theme/tiramisu`.
     - Sprawdzone archiwum wydania zawiera licencję CC BY-NC-SA 3.0.
     - Właściciel projektu potwierdził 2026-04-26, że model dystrybucji GlanceMap jest niekomercyjny.
   - Uwagi:
     - Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
     - Przeprowadź ponowny przegląd przed zmianą modelu na komercyjny.

13. Przegląd niekomercyjnej licencji dołączonego motywu Hike, Ride & Sight
   - Stan: `DONE`
   - Dowód:
     - Wersja Hike, Ride & Sight jest dołączona w `app/src/main/assets/theme/hike-ride-sight`.
     - Nagłówek sprawdzonego XML deklaruje CC BY-NC-SA 3.0.
     - Właściciel projektu potwierdził 2026-04-26 otrzymanie zgody na użycie/rozpowszechnianie przez GlanceMap.
     - Wymagane odnośniki z uznaniem autorstwa są zawarte w `licenses/CREDITS_AND_THANKS.md`.
   - Uwagi:
     - Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
     - Przeprowadź ponowny przegląd przed zmianą modelu na komercyjny.

14. Przegląd niekomercyjnej licencji dołączonego motywu Voluntary
   - Stan: `DONE`
   - Dowód:
     - Wersja Voluntary jest dołączona w `app/src/main/assets/theme/voluntary`.
     - Nagłówek sprawdzonego XML i oryginalna strona podziękowań deklarują CC BY-NC-SA 3.0.
     - Właściciel projektu potwierdził 2026-04-29 otrzymanie zgody twórcy na integrację z GlanceMap.
     - Aplikacja towarzysząca zawiera legendę i odnośniki źródłowe.
   - Uwagi:
     - Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
     - Przechowuj oryginalną zgodę poza publicznym repozytorium i zarchiwizuj prywatnie daty prośby/odpowiedzi oraz zatwierdzony zakres.
     - Przeprowadź ponowny przegląd przed zmianą modelu na komercyjny.

15. Przegląd niekomercyjnej licencji dołączonego motywu OS Map
   - Stan: `DONE`
   - Dowód:
     - Wersje dzienna/nocna OS Map V4 są dołączone w `app/src/main/assets/theme/os-map`.
     - Nagłówek sprawdzonego XML deklaruje CC BY-NC-SA 3.0.
     - Aplikacja towarzysząca zawiera legendę i odnośniki do dyskusji.
   - Uwagi:
     - Zachowaj informacje o autorstwie i obowiązki share-alike w dokumentacji projektu.
     - Przeprowadź ponowny przegląd przed zmianą modelu na komercyjny.

## Warunki upublicznienia repozytorium i wydania

Przed upublicznieniem repozytorium lub wydaniem aplikacji rozwiąż wszystkie pozycje `BLOCKER`.
Nierozwiązane pozycje `PENDING` dotyczące rozpowszechniania/licencji dołączonych zasobów traktuj jako
blokujące, chyba że dane zasoby zostaną usunięte z publicznej gałęzi lub
pozostałe ryzyko zostanie wyraźnie zaakceptowane. Upublicznienie tego repozytorium
udostępnia też dołączone zasoby stron trzecich.

Bieżący wynik oceny:
- `READY FROM A BUNDLED-ASSET LICENSING STANDPOINT`, bez pozostałych odnotowanych przeszkód w rozpowszechnianiu obecnego zestawu motywów.
- Zgoda OpenAndroMaps/Elevate oraz dowody w źródłowym readme/licencji nie stanowią już przeszkód na podstawie potwierdzenia właściciela projektu i zapisanego zapasowego readme.
- OpenHiking, French Kiss, Tiramisu, Hike, Ride & Sight, Voluntary i OS Map nie stanowią już przeszkód na podstawie zapisanych potwierdzeń, gdzie mają zastosowanie, oraz obecnego niekomercyjnego modelu dystrybucji tam, gdzie jest istotny.
- Pozostałe zadania przed upublicznieniem to sprawy operacyjne w `docs/PUBLIC_REPO_CHECKLIST.md`, takie jak metadane GitHub, włączenie Pages i zgłaszanie problemów bezpieczeństwa.

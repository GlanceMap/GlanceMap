# Polityka prywatności GlanceMap

Ostatnia aktualizacja: 2026-05-31

Ta polityka prywatności dotyczy GlanceMap, w tym aplikacji na zegarek Wear OS i towarzyszącej aplikacji na Androida, rozpowszechnianych pod nazwą GlanceMap i pakietem `com.glancemap.glancemapwearos`.

Kontakt w sprawach prywatności: `Glancemap@protonmail.com`

## Podsumowanie

GlanceMap działa przede wszystkim na Twoich urządzeniach. Większość nawigacji, renderowania map, przechowywania plików, ustawień i transferów telefon/zegarek odbywa się lokalnie na zegarku i telefonie.

Nie wymagamy konta do korzystania z aplikacji. Nie sprzedajemy Twoich danych osobowych. Nie używamy Twojej lokalizacji ani plików do reklam.

## Do jakich danych mamy dostęp i dlaczego

### 1. Dane lokalizacji na zegarku i w aplikacji towarzyszącej

- Dokładna lub przybliżona lokalizacja, gdy udzielisz dostępu.
- Używana do bieżącej nawigacji, centrowania mapy, prowadzenia po trasie, ETA, funkcji kompasu/lokalizacji i powiązanych funkcji GPS na pierwszym planie.
- Jeśli użyjesz śledzenia na żywo w aplikacji towarzyszącej, lokalizacja telefonu jest wysyłana do Arkluz, by publikować Twój bieżący ślad dla skonfigurowanej grupy.
- W obecnej sprawdzonej konfiguracji GlanceMap nie żąda `ACCESS_BACKGROUND_LOCATION`. Dostęp do lokalizacji jest wymagany do użycia na pierwszym planie.

### 2. Pliki i treści użytkownika

- Pliki, które wybierasz do importu, otwarcia, wygenerowania, przechowania, transferu lub eksportu, w tym `.gpx`, `.map`, `.poi`, `.rd5` i pliki diagnostyczne.
- Używane do wyświetlania map, tras, POI, danych tras, transferów telefon/zegarek i opcjonalnego eksportu diagnostyki.
- Zależnie od zawartości pliki te mogą zawierać dane osobowe lub poufne.

### 3. Informacje o urządzeniu, aplikacji i połączeniu

- Wersja aplikacji, model urządzenia, wersja Android/Wear OS, stan powiadomień i uprawnień lokalizacji/Bluetooth/powiadomień, stan Bluetooth/Wi-Fi/sieci, informacje o pobliskich zegarkach, stan transferu, identyfikatory wybranego zegarka i lokalne metadane plików, takie jak nazwy i rozmiary.
- Używane do parowania, transferów, zgodności, powiadomień, debugowania i wydajności.

### 4. Diagnostyka i informacje o awariach

- Aplikacja może przechowywać na urządzeniu lokalne informacje o awariach i logi diagnostyczne.
- Przy ręcznym eksporcie diagnostyki raport może zawierać szczegóły urządzenia i aplikacji, wiersze logów, stosy wywołań awarii, informacje o transferach, znaczniki czasu, nazwy wybranych plików oraz — jeśli włączono zbieranie diagnostyki — telemetrię lokalizacji lub GNSS.
- Diagnostyka służy rozwiązywaniu problemów i opuszcza urządzenie tylko wtedy, gdy wyraźnie wybierzesz jej wysłanie lub eksport.

### 5. Ustawienia i preferencje lokalne

- Preferencje takie jak jednostki, interwał GPS, opcje map, parametry ostatniego importu lub pobierania, historia transferów i wybór motywów.
- Ustawienia śledzenia na żywo, takie jak nazwa grupy, hasło uczestnika, hasło widza, nazwa uczestnika, opcjonalne adresy e-mail powiadomień i alertów, interwał alertu bezruchu, szkice komentarzy i wybrany GPX odniesienia.
- Używane do zapamiętywania konfiguracji i wygody przy kolejnych uruchomieniach.

## Kiedy dane opuszczają urządzenie

Dane mogą opuścić urządzenie w następujących sytuacjach:

### 1. Pobieranie na żądanie użytkownika od zewnętrznych dostawców danych

Gdy żądasz danych POI online, tras, wysokości, podglądu mapy lub podobnych danych, aplikacja wysyła wybranemu dostawcy informacje potrzebne do realizacji żądania, np. wybrany obszar geograficzny (prostokąt graniczny), nazwy kafelków tras, współrzędne wyświetlanych kafelków mapy lub żądane pliki.

Jak przy zwykłych żądaniach internetowych, dostawcy mogą też otrzymać standardowe metadane, takie jak adres IP, czas żądania, identyfikator user-agent lub podobne informacje o połączeniu.

Obecna konfiguracja aplikacji korzysta m.in. z:

- `refuges.info`
- `overpass-api.de`
- `tile.openstreetmap.org`
- `brouter.de`
- `download.mapsforge.org`
- `arkluz.com`

Jeśli otworzysz z aplikacji towarzyszącej zewnętrzne strony pobierania map lub GPX, obowiązują na nich ich własne polityki prywatności.

### 2. Opcjonalne śledzenie na żywo przez Arkluz

Gdy uruchamiasz śledzenie na żywo w aplikacji towarzyszącej, wysyła ona do Arkluz pozycję GPS, znacznik czasu, dokładność, opcjonalną wysokość i prędkość, poziom baterii, nazwę grupy, hasło i nazwę uczestnika, opcjonalne adresy e-mail powiadomień i alertów oraz ustawienia alertów bezruchu.

Jeśli dodasz trasę GPX lub komentarz, one też są wysyłane do Arkluz dla grupy śledzenia. Jeśli wysyłanie przez sieć się nie powiedzie, aplikacja może tymczasowo zapisać lokalnie oczekujące punkty GPS i ponowić wysyłanie później. Oczekuje się, że Arkluz automatycznie usuwa ślady i pozycje śledzenia na żywo po 7 dniach.

### 3. Transfer plików telefon/zegarek

Wybrane pliki są przesyłane między telefonem a zegarkiem kanałami komunikacji Google Wear OS, a w niektórych przypadkach przez chroniony tokenem lokalny transfer HTTP Wi-Fi w Twojej sieci lokalnej.

Nie prowadzimy dedykowanej usługi chmurowej dla tych transferów.

### 4. Eksport diagnostyki inicjowany przez użytkownika

Jeśli wybierzesz eksport lub wysłanie diagnostyki e-mailem, wybrana zawartość jest dołączana do szkicu e-maila lub udostępniana przez wybraną aplikację. Dostawca poczty lub inne wybrane miejsce docelowe obsługuje transmisję według własnych zasad.

### 5. Opcjonalne usługi Firebase, jeśli skonfigurowano je w kompilacji wydania

Kod GlanceMap zawiera opcjonalną ścieżkę przyszłej integracji z Firebase Analytics i Firebase Crashlytics.

W obecnej sprawdzonej konfiguracji Firebase nie jest włączone, a GlanceMap nie wysyła tam analityki ani raportów o awariach.

Jeśli Firebase zostanie włączone w przyszłym wydaniu, Google lub Firebase mogą przetwarzać zdarzenia analityczne, diagnostykę awarii, metadane urządzenia/aplikacji i powiązane informacje o wydajności zgodnie z warunkami i zasadami prywatności Google. Przed wydaniem należy wtedy ponownie sprawdzić formularz Bezpieczeństwo danych Google Play i tę politykę.

## Udostępnianie

Możemy udostępniać dane wyłącznie w zakresie potrzebnym do funkcji, które wybierasz:

- Składnikom komunikacji Wear OS / usług Google Play łączącym telefon z zegarkiem i przesyłającym między nimi dane.
- Zewnętrznym dostawcom, z których bezpośrednio korzystasz do pobierania map, POI, tras lub terenu.
- Arkluz, gdy używasz opcjonalnego śledzenia na żywo.
- Wybranej aplikacji poczty lub udostępniania i dalszemu dostawcy, gdy ręcznie wysyłasz diagnostykę lub eksportowane pliki.
- Firebase lub Google tylko wtedy, gdy Firebase jest włączone w zainstalowanym wydaniu.

Nie sprzedajemy Twoich danych osobowych.

## Przechowywanie, okres retencji i usuwanie

- Importowane mapy, pliki GPX, bazy POI, pakiety tras, ustawienia i podobne dane robocze są zwykle przechowywane lokalnie do ich usunięcia, wyczyszczenia danych aplikacji lub odinstalowania.
- Tymczasowa pamięć podręczna może być usuwana automatycznie lub przez wbudowane działania czyszczenia.
- Historia transferów, ostatnie parametry pobierania/importu i stan pomocy mogą pozostać lokalnie do wyczyszczenia, nadpisania lub usunięcia z danymi aplikacji.
- Ustawienia grupy śledzenia na żywo, hasła, ustawienia e-mail, szkice komentarzy, wybrany GPX odniesienia i pozycje oczekujące na ponowną wysyłkę mogą pozostać lokalnie do wyczyszczenia, nadpisania, wysłania, wylogowania, usunięcia w ramach czyszczenia lub wraz z danymi aplikacji.
- Lokalne pliki awarii i diagnostyki mogą pozostać na urządzeniu do nadpisania, wyczyszczenia lub odinstalowania aplikacji.
- Pliki diagnostyczne przygotowane do e-maila mogą pozostać w pamięci podręcznej do wyczyszczenia przez aplikację, system lub Ciebie.
- GlanceMap nie wymaga konta, więc nie ma profilu chmurowego powiązanego z kontem do usunięcia.

## Bezpieczeństwo

- Aplikacja przechowuje dane głównie w prywatnej pamięci aplikacji i w razie potrzeby używa Android `FileProvider` do kontrolowanego udostępniania plików.
- Żądania do zewnętrznych dostawców danych w obecnej konfiguracji zazwyczaj używają HTTPS.
- Żądania śledzenia na żywo do Arkluz używają HTTPS w konfiguracji wydania.
- Lokalny transfer Wi-Fi z telefonu na zegarek używa obecnie chronionego tokenem połączenia HTTP w Twojej sieci i nie należy go traktować jak internetowego magazynu z szyfrowaniem end-to-end.
- Żaden sposób przechowywania ani transmisji nie jest całkowicie bezpieczny, a wybrane usługi i aplikacje zewnętrzne mają własne zasady bezpieczeństwa.

## Twój wybór

Możesz:

- Przyznawać lub odmawiać uprawnień do lokalizacji, powiadomień i Bluetooth.
- Decydować, które pliki importować, generować, przesyłać i eksportować.
- Decydować o tworzeniu/dołączaniu do grupy śledzenia na żywo, uruchamianiu/zatrzymywaniu śledzenia, dodawaniu GPX/komentarzy i konfiguracji e-maili powiadomień oraz alertów.
- Decydować o zbieraniu diagnostyki i wysyłaniu jej e-mailem.
- Zarządzać lokalnymi plikami, pamięcią podręczną i danymi aplikacji lub je usuwać w aplikacji albo ustawieniach Androida.
- Odinstalować aplikację, by usunąć jej lokalne dane z urządzenia, z uwzględnieniem zwykłego działania Androida i plików wyeksportowanych gdzie indziej.

## Dzieci

GlanceMap nie jest aplikacją przeznaczoną dla dzieci.

## Zmiany tej polityki

Możemy aktualizować tę politykę wraz ze zmianami aplikacji. Przy istotnych zmianach zaktualizujemy datę „Ostatnia aktualizacja”.

## Kontakt

Pytania i wnioski dotyczące prywatności można wysyłać na:

`Glancemap@protonmail.com`

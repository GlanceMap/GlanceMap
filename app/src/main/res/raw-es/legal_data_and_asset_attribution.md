# Atribución de datos y recursos

Última revisión: 2026-05-07

Este archivo documenta atribución y licencias de recursos cartográficos no ejecutables, conjuntos de datos y fuentes POI importadas usados por GlanceMap.

## 1) Datos de OpenStreetMap

- Texto de atribución recomendado:
  - `Map data (c) OpenStreetMap contributors`
- Fuentes:
  - https://www.openstreetmap.org/copyright
  - https://opendatacommons.org/licenses/odbl/1-0/

## 2) Tema cartográfico Elevate (ecosistema OpenAndroMaps)

- Archivo local del tema:
- `app/src/main/assets/Elevate.xml`
- `app/src/main/assets/theme/elevate/`
- `app/src/main/assets/theme/elevate-winter/`
- `app/src/main/assets/theme/elevate-winter-white/`
- Declaración de licencia incluida en el archivo:
  - CC BY-NC-SA 4.0 para el estilo cartográfico.
  - La nota de uso comercial remite a BY-ND 4.0.
- Referencias originales:
  - https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme
  - https://www.openandromaps.org/en/license

Importante:
- `Elevate.xml` remite a `read_me_elevate.txt` para las licencias de reutilización de recursos.
- Las copias versionadas de los temas incluyen `read_me_elevate.txt`; las compilaciones normales no descargan recursos de temas.
- El respaldo fuente raíz mantiene `app/src/main/assets/read_me_elevate.txt` junto a `Elevate.xml`.
- La página de licencia de OpenAndroMaps incluye restricciones de inclusión y permisos.
- El responsable del proyecto comunica haber recibido de OpenAndroMaps y Tobias Kuehn el permiso de uso e integración de OpenAndroMaps/Elevate en GlanceMap.
- Registrar las fechas exactas del acuerdo y su alcance aprobado fuera del repositorio público antes de cambiar la visibilidad.

## 3) Tema Mapsforge OpenHiking

- Archivos locales del tema incluido:
  - `app/src/main/assets/theme/openhiking/OpenHiking.xml`
  - Árbol de recursos `app/src/main/assets/theme/openhiking/`
- Versión original fijada en la configuración:
  - `2026-02-10`
- Archivo opcional para actualizar durante la compilación:
  - `https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus`
- Contenido del tema extraído observado el 2026-03-14:
  - `OpenHiking/OpenHiking.xml`
  - Subcarpetas SVG, patrones y símbolos incluidas en el ZIP.
- Referencias originales:
  - https://openhiking.eu/en/downloads/mapsforge-maps
  - https://openhiking.eu/locus/Hungary.xml

Importante:
- El ZIP descargado no contenía un archivo independiente evidente de licencia o instrucciones.
- El responsable del proyecto confirmó el 2026-04-26 la aprobación de OpenHiking.
- El responsable del proyecto confirmó que la licencia del tema OpenHiking es `CC BY-SA`.
- Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
- Archivar en privado la página o versión original exacta y los detalles de aprobación cuando estén disponibles.
- Nota para usuarios disponible en `licenses/OPENHIKING_THEME.md`.

## 4) Tema Mapsforge Tiramisu

- Archivos locales del tema incluido:
  - `app/src/main/assets/theme/tiramisu/Tiramisu.xml`
  - Árbol de recursos `app/src/main/assets/theme/tiramisu/res/`
- Origen de la copia incluida:
  - https://github.com/IgorMagellan/Tiramisu/releases/tag/v4.4
- Contenido de la distribución observado el 2026-03-18:
  - `Tiramisu.xml`
  - Árbol de recursos SVG `res/`
  - `LICENSE`
  - `README.md`
- Licencia declarada en el paquete original:
  - CC BY-NC-SA 3.0

Importante:
- La licencia original no permite uso comercial.
- El responsable confirmó el 2026-04-26 que la distribución de GlanceMap no es comercial, por lo que el plan actual de repositorio y app públicos se considera compatible.
- Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
- Revisar de nuevo si la distribución pasa a ser comercial.
- Nota para usuarios disponible en `licenses/TIRAMISU_THEME.md`.

## 5) Tema Mapsforge French Kiss

- Archivos locales del tema incluido:
  - `app/src/main/assets/theme/frenchkiss/frenchkiss.xml`
  - `app/src/main/assets/theme/frenchkiss/motifs/`
- Origen de la copia usada en el paquete local actual:
  - `xctrack-beta-latest.apk` aportado por el usuario
- Contenido de la copia observado el 2026-03-18:
  - `frenchkiss.xml`
  - Árbol de recursos PNG `motifs/`
- Declaración de procedencia incluida en la cabecera XML:
  - `IGN TOP25 theme for XCTrack by Pascal Cochet`

Importante:
- No se observó un archivo de licencia independiente junto a los recursos extraídos del tema.
- El responsable del proyecto confirmó el 2026-04-27 la aprobación del desarrollador de French Kiss.
- Conservar la atribución actual en los créditos salvo que el autor original solicite otra redacción más específica.
- Archivar en privado los detalles exactos de aprobación.
- Nota para usuarios disponible en `licenses/FRENCH_KISS_THEME.md`.

## 6) Tema Mapsforge Hike, Ride & Sight

- Archivos locales del tema incluido:
  - `app/src/main/assets/theme/hike-ride-sight/HikeRideSight.xml`
  - `app/src/main/assets/theme/hike-ride-sight/PATTERNS/`
  - `app/src/main/assets/theme/hike-ride-sight/SYMBOLS/`
- Página del tema:
  - http://j.seydoux.free.fr/locus/hrs.html
- Descarga ZIP manual:
  - http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.zip
- Declaración de licencia original observada:
  - Declarada en el comentario de cabecera XML como CC BY-NC-SA 3.0

Importante:
- No se observó un archivo de licencia separado en el ZIP descargado.
- La licencia declarada no permite uso comercial.
- El responsable del proyecto confirmó el 2026-04-26 la aprobación del uso y redistribución por GlanceMap.
- La distribución no comercial actual de GlanceMap se considera compatible.
- Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
- Los enlaces requeridos para usuarios se conservan en `licenses/CREDITS_AND_THANKS.md`.
- Nota para usuarios disponible en `licenses/HIKE_RIDE_SIGHT_THEME.md`.

## 7) Tema Mapsforge Voluntary

- Archivos locales del tema incluido:
  - `app/src/main/assets/theme/voluntary/Voluntary V5.xml`
  - `app/src/main/assets/theme/voluntary/vol_res/`
- Sitio web del tema:
  - https://voluntary.nichesite.org/
- Descarga ZIP manual:
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20MF5.zip
- Leyenda:
  - https://voluntary.nichesite.org/key.html
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20Key.pdf
- Declaración de licencia original observada:
  - Declarada en el comentario de cabecera XML y en los créditos originales como CC BY-NC-SA 3.0

Importante:
- No se observó un archivo de licencia separado en el ZIP descargado.
- La licencia declarada no permite uso comercial.
- El responsable del proyecto confirmó el 2026-04-29 la aprobación del creador para integrar el tema en GlanceMap.
- La distribución no comercial actual de GlanceMap se considera compatible.
- Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
- Nota para usuarios disponible en `licenses/VOLUNTARY_THEME.md`.

## 8) Tema Mapsforge OS Map

- Archivos locales del tema incluido:
  - `app/src/main/assets/theme/os-map/OS Map V4 Day.xml`
  - `app/src/main/assets/theme/os-map/OS Map V4 Night.xml`
  - `app/src/main/assets/theme/os-map/os_res/`
- Debate del tema:
  - https://forum.locusmap.eu/index.php?topic=7000.msg59948#msg59948
- ZIP de distribución:
  - https://drive.google.com/uc?export=download&id=1RzZTMLRIQM1pY_R8FXdVxKPSTqO2LHUE
- Leyendas:
  - https://drive.google.com/file/d/1PE0eBzJnGMbDs9a_V_uhQQa0db5RK-Zs/view
  - https://drive.google.com/file/d/1OwAeuBtYN-XxjGkpOs3SrdYAUwYXDets/view
- Declaración de licencia original observada:
  - Declarada en el comentario de cabecera XML como CC BY-NC-SA 3.0

Importante:
- La licencia declarada no permite uso comercial.
- La distribución no comercial actual de GlanceMap se considera compatible.
- Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
- Nota para usuarios disponible en `licenses/OS_MAP_THEME.md`.

## 9) Importaciones de datos Refuges.info

- La app del teléfono importa datos POI de:
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`
- Referencia de licencia:
  - https://www.refuges.info/wiki/licence

Notas:
- Refuges.info indica que el contenido de las páginas de puntos está bajo CC BY-SA.
- Refuges.info también remite a los datos OpenStreetMap y a las restricciones ODbL cuando corresponda.

## 9) Ampliación de POI OSM mediante Overpass API

- Punto de acceso usado por la app del teléfono:
  - `https://overpass-api.de/api/interpreter`
- Documentación:
  - https://wiki.openstreetmap.org/wiki/Overpass_API

Nota de uso:
- Las instrucciones de la instancia pública indican límites prudentes de consultas y ancho de banda diarios.

## 10) Datos de altitud DEM (dibujo de pendientes y relieve)

- La compilación y la app usan teselas `.hgt.zip` de tipo DEM3 y, opcionalmente, teselas `.hgt.gz` de 1 segundo de arco.
- Fuente predeterminada de la app y la compilación:
  - `https://download.mapsforge.org/maps/dem/dem3`
- Fuente opcional de 1 segundo de arco:
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`
- Referencias:
  - https://download.mapsforge.org/maps/dem/
  - https://download.mapsforge.org/maps/dem/ReadMe.md
  - https://www.mapzen.com/blog/terrain-tile-service/
  - https://www.mapzen.com/rights/

## 11) Recursos de iconos POI

- Los iconos POI locales derivan de los conjuntos SVG Maki y Temaki:
  - https://github.com/mapbox/maki
  - https://github.com/rapideditor/temaki
- La compilación también puede descargar iconos SVG Maki de respaldo desde:
  - https://github.com/mapbox/maki
- Licencia:
  - CC0 1.0 Universal
  - https://creativecommons.org/publicdomain/zero/1.0/

## 12) Recursos y segmentos de rutas BRouter

- Recursos de perfiles de rutas incluidos:
  - `app/src/main/assets/brouter/profiles2/lookups.dat`
  - `app/src/main/assets/brouter/profiles2/hiking-mountain.brf`
- Fuente de descarga de segmentos de rutas en la app del teléfono:
  - `https://brouter.de/brouter/segments4`
- Referencias originales:
  - https://github.com/abrensch/brouter
  - https://brouter.de/brouter/

Notas:
- BRouter indica que los segmentos de rutas `.rd5` se generan a partir de datos OpenStreetMap y se actualizan semanalmente.
- La atribución OpenStreetMap sigue siendo pertinente para los datos de segmentos de rutas.
- La app del reloj incorpora el código BRouter por separado bajo la licencia MIT; consulta el aviso de licencia del código en `THIRD_PARTY_NOTICES.md`.

## 13) Archivos importados por el usuario

- La app admite mapas, POI y archivos GPX importados por el usuario.
- Las licencias y los derechos de redistribución de los archivos aportados siguen siendo responsabilidad del usuario.

## 14) Lista práctica de cumplimiento

- Mantener una pantalla legal accesible desde los ajustes del reloj.
- Mantener `CREDITS_AND_THANKS.md` breve y comprensible.
- Sincronizar la documentación legal detallada con los enlaces y API externos de la app del teléfono.
- Revisar las condiciones cuando cambien URL de proveedores o fuentes de importación.

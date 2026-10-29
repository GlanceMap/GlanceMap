# Condiciones de servicios y uso de API

Última revisión: 2026-05-06

Este documento recoge restricciones legales y de uso, ajenas a las bibliotecas, de los servicios y proveedores externos referenciados por la app y la compilación.

## 1) OpenAndroMaps y Elevate

Usado para:
- Recomendaciones de descargas de mapas en la app del teléfono.
- Descarga del tema Elevate y recursos locales de respaldo durante la compilación y ejecución del reloj.

Referencias:
- https://www.openandromaps.org/en/license
- https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme

Notas importantes de condiciones (de la página de licencia original):
- Se indica que los enlaces directos a mapas están prohibidos.
- Vender mapas o incluirlos en una app exige permiso escrito del proveedor.
- En general se permite integrar temas "andromaps_" en servicios de actualización gratuitos.
- Integrar temas de Tobias, incluido Elevate, exige permiso escrito de Tobias.

Estado del proyecto:
- El responsable del proyecto comunica haber recibido de OpenAndroMaps y Tobias Kuehn el permiso de uso e integración de OpenAndroMaps/Elevate en GlanceMap.
- Conservar los mensajes originales del acuerdo fuera del repositorio público.
- Antes de cambiar la visibilidad en GitHub, registrar fechas de solicitud/respuesta y alcance aprobado en notas privadas de publicación o una incidencia interna.

## 2) Tema Mapsforge OpenHiking

Usado para:
- Recursos locales del tema OpenHiking incluidos en la app del reloj.
- Descarga opcional de actualización de OpenHiking durante la compilación del reloj.
- Referencia al sitio de mapas y temas OpenHiking en la app del teléfono.

Referencias:
- https://openhiking.eu/en/downloads/mapsforge-maps
- https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus

Notas de condiciones:
- El sitio público ofrece enlaces directos de descarga e instalación, pero no se encontró una API general documentada.
- El ZIP del tema descargado y examinado el 2026-03-14 no contenía un archivo independiente evidente de licencia o instrucciones.

Estado del proyecto:
- El responsable del proyecto confirmó el 2026-04-26 la aprobación de OpenHiking.
- El responsable del proyecto confirmó que la licencia del tema OpenHiking es `CC BY-SA`.
- Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
- Documentar los detalles de la copia actual en `licenses/OPENHIKING_THEME.md`.

## 3) Tema Mapsforge Voluntary

Usado para:
- Recursos locales del tema Voluntary incluidos en la app del reloj.
- Referencias al sitio y leyenda del tema Voluntary en la app del teléfono.

Referencias:
- https://voluntary.nichesite.org/
- https://voluntary.nichesite.org/downloads.html
- https://voluntary.nichesite.org/key.html
- https://voluntary.nichesite.org/credits.html

Notas de condiciones:
- El sitio público ofrece enlaces de descarga manual y leyenda, pero no se encontró una API general documentada.
- El ZIP del tema descargado y examinado el 2026-04-29 no contenía un archivo independiente evidente de licencia o instrucciones.
- La cabecera XML y la página de créditos identifican la licencia como CC BY-NC-SA 3.0.

Estado del proyecto:
- El responsable del proyecto confirmó el 2026-04-29 la aprobación del creador para integrar el tema en GlanceMap.
- Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
- Documentar los detalles de la copia actual en `licenses/VOLUNTARY_THEME.md`.

## 4) API y datos de Refuges.info

Usado para:
- Solicitudes API de la app del teléfono a:
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`

Referencias:
- Raíz de API: https://www.refuges.info/api
- Página de licencia: https://www.refuges.info/wiki/licence

Notas de condiciones:
- El contenido de las páginas de puntos Refuges.info se publica bajo Creative Commons BY-SA.
- Refuges.info también reutiliza datos OpenStreetMap bajo ODbL cuando corresponde.

## 5) OpenStreetMap y Overpass

Usado para:
- Atribución OSM en la app.
- Teselas ráster de fondo del selector de mapas de la app del teléfono:
  - `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
- Ampliación de POI OSM mediante Overpass API:
  - `https://overpass-api.de/api/interpreter`

Referencias:
- Derechos de autor y atribución OSM: https://www.openstreetmap.org/copyright
- Política de uso de teselas OSM: https://operations.osmfoundation.org/policies/tiles/
- ODbL: https://opendatacommons.org/licenses/odbl/1-0/
- Documentación de Overpass API: https://wiki.openstreetmap.org/wiki/Overpass_API

Nota de política de uso:
- Las teselas OSM deben usarse solo para visualización interactiva normal. No añadir precarga, descarga masiva ni caché de teselas sin conexión contra `tile.openstreetmap.org`.
- Las teselas OSM requieren atribución visible, URL HTTPS oficial, un User-Agent claro de la app y caché HTTP que respete las cabeceras del servidor.
- Las recomendaciones de la instancia pública Overpass indican un uso prudente en torno a:
  - menos de 10000 consultas/día
  - menos de 1 GB descargado/día

## 6) Motor de rutas y segmentos BRouter

Usado para:
- Cálculo de rutas sin conexión en el reloj mediante módulos BRouter incorporados.
- Descarga de segmentos de rutas `.rd5` desde la app del teléfono de:
  - `https://brouter.de/brouter/segments4`

Referencias:
- Proyecto: https://github.com/abrensch/brouter
- Sitio oficial: https://brouter.de/brouter/
- Carpeta de segmentos: https://brouter.de/brouter/segments4/

Notas de condiciones y atribución:
- El proyecto original indica que BRouter se publica bajo licencia MIT.
- El proyecto original indica que los segmentos `.rd5` se generan desde datos OpenStreetMap y se actualizan semanalmente.
- Por ello, la atribución OpenStreetMap y el contexto ODbL siguen siendo pertinentes para los datos de rutas BRouter.

Nota del proyecto:
- No se identificó una página pública independiente sobre política de servicio o límites de uso en las páginas originales BRouter revisadas el 2026-03-20.
- Conclusión del proyecto: mantener descargas prudentes en la app del teléfono y revisar las condiciones originales antes de automatizar a gran escala.

## 7) DEM y fuentes del terreno

Usado para:
- Descargas DEM3 de:
  - `https://download.mapsforge.org/maps/dem/dem3`
- Descargas DEM opcionales de 1 segundo de arco de:
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`

Referencias:
- https://download.mapsforge.org/maps/dem/
- https://download.mapsforge.org/maps/dem/ReadMe.md
- https://www.mapzen.com/blog/terrain-tile-service/
- https://www.mapzen.com/rights/

## 8) Fuente de iconos POI durante la compilación

Usado para:
- Descarga de iconos SVG Maki durante la compilación.

Referencias:
- https://github.com/mapbox/maki
- Licencia: CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/)

## 9) Contexto de condiciones de Google Play services

Usado para:
- Dependencias de ejecución `play-services-wearable` y `play-services-location`.

Referencias:
- Acuerdo de licencia del SDK Android: https://developer.android.com/studio/terms
- Guía de avisos de código abierto de Google Play services: https://developers.google.com/android/guides/opensource

Nota:
- Estas son condiciones de SDK y servicios Google, no licencias de código abierto estándar como Apache o LGPL.

## 10) Declaración de no afiliación

La app remite a proveedores y sitios externos por comodidad del usuario. Salvo declaración expresa de dichos proveedores, GlanceMap no está afiliado oficialmente ni respaldado por ellos.

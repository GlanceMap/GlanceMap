# Política de privacidad de GlanceMap

Última actualización: 2026-05-31

Esta política se aplica a GlanceMap, incluida la app Wear OS del reloj y su app complementaria Android, distribuidas con el nombre GlanceMap y el paquete `com.glancemap.glancemapwearos`.

Contacto de privacidad: `Glancemap@protonmail.com`

## Resumen

GlanceMap está diseñado para funcionar principalmente en tus propios dispositivos. La mayor parte de la navegación, el dibujo de mapas, el almacenamiento, los ajustes y las transferencias entre teléfono y reloj se realizan localmente en ambos dispositivos.

No exigimos crear una cuenta para usar la app. No vendemos tus datos personales. No usamos tu ubicación ni tus archivos con fines publicitarios.

## Datos a los que accedemos y motivos

### 1. Datos de ubicación en el reloj y la app del teléfono

- Ubicación precisa o aproximada, si das permiso.
- Se usa para navegación en directo, centrado del mapa, guiado de rutas, hora estimada de llegada, brújula y ubicación, y funciones GPS relacionadas en primer plano.
- Si eliges Seguimiento en directo en la app del teléfono, la ubicación del teléfono se envía a Arkluz para publicar tu recorrido en el grupo configurado.
- En la configuración actual revisada, GlanceMap no solicita `ACCESS_BACKGROUND_LOCATION`. El permiso de ubicación se solicita para uso en primer plano.

### 2. Archivos y contenido del usuario

- Archivos que decides importar, abrir, generar, guardar, transferir o exportar, incluidos `.gpx`, `.map`, `.poi`, `.rd5` y archivos de diagnóstico.
- Se usan para mostrar mapas, rutas, POI y datos de rutas, transferir entre teléfono y reloj y exportar diagnósticos opcionales.
- Estos archivos pueden contener información personal o sensible según su contenido.

### 3. Información de dispositivo, app y conexión

- Versión de app, modelo de dispositivo, versión Android/Wear OS, estado de notificaciones y permisos de ubicación, Bluetooth y notificaciones; estado de Bluetooth, Wi-Fi y red; relojes cercanos, estado de transferencias, identificadores de relojes seleccionados y metadatos locales como nombres y tamaños de archivos.
- Se usa para vinculación, transferencias, compatibilidad, notificaciones, depuración y rendimiento.

### 4. Diagnóstico e información de cierres inesperados

- La app puede guardar notas de cierres inesperados y registros de diagnóstico localmente en el dispositivo.
- Si exportas diagnósticos manualmente, el informe puede incluir detalles de app y dispositivo, líneas de registro, trazas de errores, transferencias, fechas y horas, nombres de archivos seleccionados y, si la captura está activada, telemetría de ubicación o GNSS.
- Los diagnósticos sirven para resolver problemas y solo salen del dispositivo cuando decides expresamente enviarlos o exportarlos.

### 5. Ajustes y preferencias locales

- Preferencias como unidades, intervalo GPS, opciones del mapa, últimos parámetros de importación o descarga, historial de transferencias y temas elegidos.
- Ajustes de Seguimiento en directo como nombre de grupo, contraseñas de participante y espectador, nombre de participante, correos opcionales para notificaciones y alertas, intervalo de alerta por inactividad, comentarios en borrador y referencia GPX seleccionada.
- Se usan para recordar tu configuración y facilitar usos posteriores.

## Cuándo salen datos de tu dispositivo

Los datos pueden salir de tu dispositivo en estas situaciones:

### 1. Descargas solicitadas por el usuario a proveedores externos

Al solicitar POI, rutas, altitud, vistas previas de mapas o datos similares en línea, la app envía al proveedor elegido lo necesario para atender la solicitud: por ejemplo, el área geográfica seleccionada (cuadro delimitador), nombres de teselas de rutas, coordenadas de teselas del mapa o archivos solicitados.

Como en las solicitudes normales de internet, los proveedores también pueden recibir metadatos como tu dirección IP, hora de solicitud, agente de usuario u otros datos de conexión.

Proveedores usados por la configuración actual de la app:

- `refuges.info`
- `overpass-api.de`
- `tile.openstreetmap.org`
- `brouter.de`
- `download.mapsforge.org`
- `arkluz.com`

Los sitios externos de descarga de mapas o GPX abiertos desde la app del teléfono se rigen por sus propias políticas de privacidad.

### 2. Seguimiento en directo opcional mediante Arkluz

Al iniciar Seguimiento en directo desde la app del teléfono, se envían a Arkluz posición GPS, fecha y hora, precisión, altitud y velocidad opcionales, batería, nombre del grupo, contraseña y nombre del participante, correos opcionales de notificación y alerta, y ajustes de alertas por inactividad.

Si añades una ruta GPX o un comentario, también se suben a Arkluz para el grupo. Si falla la red, la app puede guardar puntos GPS en cola localmente y reintentar más tarde. Se espera que Arkluz elimine automáticamente recorridos y posiciones en directo después de 7 días.

### 3. Transferencia de archivos entre teléfono y reloj

Los archivos elegidos se transfieren entre teléfono y reloj por los canales de comunicación Google Wear OS y, en algunos casos, por HTTP local sobre Wi-Fi protegido con un token en tu red local.

No operamos un servicio en la nube dedicado a estas transferencias.

### 4. Exportación de diagnósticos iniciada por el usuario

Si exportas o envías diagnósticos por correo, el contenido seleccionado se adjunta a un borrador o se comparte con la app elegida. Tu proveedor de correo u otro destino gestiona ese envío bajo sus propias políticas.

### 5. Servicios Firebase opcionales, si se configuran en una versión de distribución

El código de GlanceMap contiene una vía de integración futura opcional para Firebase Analytics y Firebase Crashlytics.

En la configuración actual revisada, Firebase no está activado y GlanceMap no le envía análisis ni informes de cierres inesperados.

Si se activa Firebase en una versión futura, Google o Firebase pueden tratar eventos de análisis, diagnósticos de cierres, metadatos y datos de rendimiento según sus condiciones y prácticas de privacidad. Antes de publicar, deberán revisarse de nuevo esta política y el formulario de seguridad de datos de Google Play.

## Comunicación de datos

Solo podemos compartir datos cuando sea necesario para las funciones que eliges usar:

- Con componentes de comunicación Wear OS y Google Play services que conectan el teléfono y el reloj y transfieren datos.
- Con los proveedores externos que usas directamente para descargar mapas, POI, rutas o relieve.
- Con Arkluz al usar Seguimiento en directo opcional.
- Con tu app elegida de correo o compartir y su proveedor cuando envías manualmente diagnósticos o archivos exportados.
- Con Firebase o Google solo si Firebase está activado en la versión instalada.

No vendemos tus datos personales.

## Almacenamiento, conservación y eliminación

- Los mapas importados, GPX, bases POI, paquetes de rutas, ajustes y datos similares se guardan normalmente en tus dispositivos hasta que los borras, vacías los datos de la app o la desinstalas.
- Las cachés temporales pueden borrarse automáticamente o con las funciones de limpieza.
- El historial de transferencias, los últimos parámetros de descarga o importación y el estado de ayuda pueden permanecer localmente hasta borrarse, sobrescribirse o eliminarse con los datos de la app.
- Los ajustes de grupo, contraseñas, correos, borradores, referencia GPX y posiciones pendientes de Seguimiento en directo pueden permanecer localmente hasta borrarse, sobrescribirse, enviarse, cerrar sesión, realizar una limpieza o borrar los datos de la app.
- Los archivos locales de cierres y diagnóstico pueden permanecer en el dispositivo hasta sobrescribirse, borrarse o desinstalar la app.
- Los diagnósticos preparados para correo pueden permanecer en la caché hasta que los borre la app, el sistema o tú.
- GlanceMap no exige una cuenta, por lo que no existe un perfil en la nube asociado que debas borrar.

## Seguridad

- La app guarda principalmente los datos en su almacenamiento privado y utiliza `FileProvider` de Android para compartir archivos de forma controlada cuando es necesario.
- Las solicitudes a proveedores externos en la configuración actual usan normalmente HTTPS.
- Las solicitudes de Seguimiento en directo a Arkluz usan HTTPS en la configuración de distribución.
- La transferencia Wi-Fi local del teléfono al reloj usa actualmente HTTP local protegido con un token en tu red; no debe considerarse almacenamiento de internet cifrado de extremo a extremo.
- Ningún almacenamiento o envío es perfectamente seguro; los servicios y apps de terceros que eliges tienen sus propias prácticas de seguridad.

## Tus opciones

Puedes:

- Conceder o denegar permisos de ubicación, notificaciones o Bluetooth.
- Decidir qué archivos importar, generar, transferir o exportar.
- Decidir si crear o unirte a un grupo de Seguimiento en directo, iniciarlo o detenerlo, añadir GPX y comentarios o configurar correos de notificación y alerta.
- Decidir si iniciar la captura de diagnóstico o enviarlo por correo.
- Gestionar o borrar archivos locales, cachés y datos de la app desde la app o los ajustes Android.
- Desinstalar la app para borrar sus datos locales del dispositivo, sujeto al comportamiento normal de Android y a los archivos exportados a otros destinos.

## Menores

GlanceMap no está diseñado como app infantil.

## Cambios en esta política

Podemos actualizar esta política al cambiar la app. Modificaremos la fecha «Última actualización» cuando haya cambios importantes.

## Contacto

Puedes enviar preguntas o solicitudes de privacidad a:

`Glancemap@protonmail.com`

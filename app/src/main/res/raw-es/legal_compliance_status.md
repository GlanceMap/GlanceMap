# Estado de cumplimiento

Última revisión: 2026-05-07

Leyenda de estados:
- `DONE`: implementado y verificado.
- `PENDING`: requiere seguimiento antes o después de publicar.
- `BLOCKER`: no distribuir un recurso incluido sin resolverlo.

## Lista actual de comprobaciones

1. Acceso legal para usuarios desde los ajustes del reloj
   - Estado: `DONE`
   - Prueba: pantalla `Settings -> Licenses` disponible en la app.

2. Página de créditos comprensible
   - Estado: `DONE`
   - Prueba: `licenses/CREDITS_AND_THANKS.md`.

3. Avisos de código abierto de las bibliotecas principales
   - Estado: `DONE`
   - Prueba: `licenses/THIRD_PARTY_NOTICES.md`.

4. Atribución de datos y recursos
   - Estado: `DONE`
   - Prueba: `licenses/DATA_AND_ASSET_ATTRIBUTION.md`.

5. Página de condiciones de servicios y API
   - Estado: `DONE`
   - Prueba: `licenses/SERVICE_TERMS_AND_API_USAGE.md`.

6. Transparencia de fuentes externas de la app del teléfono
   - Estado: `DONE`
   - Prueba: `licenses/COMPANION_EXTERNAL_SOURCES.md`.

7. Permiso de redistribución de OpenAndroMaps/Elevate incluidos
   - Estado: `DONE`
   - Prueba:
     - El responsable del proyecto confirmó el 2026-04-20 que OpenAndroMaps y Tobias Kuehn aceptaron el uso y la integración en GlanceMap.
   - Notas:
     - Conservar los mensajes originales del acuerdo fuera del repositorio público.
     - Antes de cambiar la visibilidad, registrar fechas de solicitud/respuesta y alcance aprobado en notas de publicación privadas o una incidencia interna.
     - El alcance debe cubrir la redistribución pública del código, la inclusión en APK/AAB y los temas o recursos Elevate modificados o adaptados usados por GlanceMap.

8. Archivo de licencia de recursos referenciado por Elevate (`read_me_elevate.txt`)
   - Estado: `DONE`
   - Prueba:
     - `app/src/main/assets/read_me_elevate.txt` está versionado junto al archivo fuente de respaldo raíz `Elevate.xml`.
   - Notas:
     - Referenciado por `app/src/main/assets/Elevate.xml`.
     - Las copias versionadas de Elevate incluyen `read_me_elevate.txt`; las compilaciones normales incluyen estos recursos locales sin descargar temas.
     - El archivo fuente de respaldo versionado conserva la información original de licencias necesaria para la revisión pública del código.

9. Límites de uso de la instancia pública Overpass
   - Estado: `PENDING`
   - Notas:
     - La política pública principal indica límites prudentes (10000 consultas/día, <1 GB/día).
     - Mantener prudente la limitación de consultas e importaciones.

10. Verificación de licencia y redistribución del tema OpenHiking incluido
   - Estado: `DONE`
   - Prueba:
     - El responsable del proyecto confirmó el 2026-04-26 la aprobación de OpenHiking.
     - El responsable del proyecto confirmó que la licencia del tema OpenHiking es `CC BY-SA`.
   - Notas:
     - La copia de OpenHiking está incluida en `app/src/main/assets/theme/openhiking`.
     - La integración ZIP del tema OpenHiking sigue disponible como actualización opcional en la compilación.
     - El ZIP examinado no contenía un archivo independiente evidente de licencia o instrucciones.
     - Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
     - Archivar en privado la página o versión original exacta y los detalles de aprobación cuando estén disponibles.

11. Verificación de licencia y redistribución del tema French Kiss incluido
   - Estado: `DONE`
   - Prueba:
     - La copia de French Kiss está incluida en `app/src/main/assets/theme/frenchkiss`.
     - La copia actual se extrajo del APK beta de XCTrack aportado por el usuario.
     - El XML incluido lo identifica como un tema IGN TOP25 para XCTrack de Pascal Cochet.
     - El responsable del proyecto confirmó el 2026-04-27 la aprobación del desarrollador de French Kiss.
   - Notas:
     - No se observó un archivo de licencia independiente junto a los recursos del tema.
     - Conservar la atribución actual en los créditos salvo que el autor original solicite otra redacción más específica.
     - Conservar el mensaje original de aprobación fuera del repositorio público y archivar en privado las fechas y el alcance aprobado.

12. Revisión de licencia no comercial del tema Tiramisu incluido
   - Estado: `DONE`
   - Prueba:
     - La copia de Tiramisu está incluida en `app/src/main/assets/theme/tiramisu`.
     - El archivo de distribución examinado incluye una licencia CC BY-NC-SA 3.0.
     - El responsable del proyecto confirmó el 2026-04-26 que la distribución de GlanceMap no es comercial.
   - Notas:
     - Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
     - Revisar de nuevo antes de cambiar a cualquier distribución comercial.

13. Revisión de licencia no comercial del tema Hike, Ride & Sight incluido
   - Estado: `DONE`
   - Prueba:
     - La copia de Hike, Ride & Sight está incluida en `app/src/main/assets/theme/hike-ride-sight`.
     - La cabecera XML examinada declara CC BY-NC-SA 3.0.
     - El responsable del proyecto confirmó el 2026-04-26 la aprobación del uso y redistribución por GlanceMap.
     - Los enlaces de atribución requeridos están en `licenses/CREDITS_AND_THANKS.md`.
   - Notas:
     - Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
     - Revisar de nuevo antes de cambiar a cualquier distribución comercial.

14. Revisión de licencia no comercial del tema Voluntary incluido
   - Estado: `DONE`
   - Prueba:
     - La copia de Voluntary está incluida en `app/src/main/assets/theme/voluntary`.
     - La cabecera XML y la página original de créditos examinadas declaran CC BY-NC-SA 3.0.
     - El responsable del proyecto confirmó el 2026-04-29 la aprobación del creador para integrar el tema en GlanceMap.
     - La leyenda y los enlaces de referencia están en la app del teléfono.
   - Notas:
     - Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
     - Conservar el mensaje original de aprobación fuera del repositorio público y archivar en privado las fechas y el alcance aprobado.
     - Revisar de nuevo antes de cambiar a cualquier distribución comercial.

15. Revisión de licencia no comercial del tema OS Map incluido
   - Estado: `DONE`
   - Prueba:
     - Las copias diurna y nocturna de OS Map V4 están en `app/src/main/assets/theme/os-map`.
     - La cabecera XML examinada declara CC BY-NC-SA 3.0.
     - La leyenda y los enlaces al debate están en la app del teléfono.
   - Notas:
     - Conservar atribución y obligaciones de compartir igual en la documentación del proyecto.
     - Revisar de nuevo antes de cambiar a cualquier distribución comercial.

## Condiciones para repositorio público y publicación

Para publicar el repositorio o la app, resolver todos los elementos `BLOCKER`.
Considerar bloqueantes los elementos `PENDING` de licencia o redistribución de recursos incluidos
salvo que los recursos afectados se retiren de la rama pública o que
se acepte expresamente el riesgo residual. Publicar el repositorio también
publica los recursos de terceros incluidos.

Resultado actual:
- `READY FROM A BUNDLED-ASSET LICENSING STANDPOINT`, sin bloqueos registrados de redistribución pendientes para los temas actuales.
- El permiso OpenAndroMaps/Elevate y las pruebas de licencia e instrucciones ya no bloquean, según la confirmación del responsable y el archivo de respaldo versionado.
- OpenHiking, French Kiss, Tiramisu, Hike, Ride & Sight, Voluntary y OS Map ya no bloquean, según las confirmaciones registradas y el modelo no comercial actual, donde corresponda.
- Las tareas previas a la publicación restantes son operativas y figuran en `docs/PUBLIC_REPO_CHECKLIST.md`, como metadatos GitHub, activar Pages y comunicar problemas de seguridad.

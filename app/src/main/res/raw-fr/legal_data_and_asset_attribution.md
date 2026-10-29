# Attribution des données et ressources

Dernière révision : 2026-05-07

Ce fichier consigne les attributions et licences des ressources cartographiques hors code, jeux de données et sources de POI importés utilisés par GlanceMap.

## 1) Données OpenStreetMap

- Attribution recommandée :
  - `Map data (c) OpenStreetMap contributors`
- Sources :
  - https://www.openstreetmap.org/copyright
  - https://opendatacommons.org/licenses/odbl/1-0/

## 2) Thème Elevate (écosystème OpenAndroMaps)

- Fichiers locaux :
- `app/src/main/assets/Elevate.xml`
- `app/src/main/assets/theme/elevate/`
- `app/src/main/assets/theme/elevate-winter/`
- `app/src/main/assets/theme/elevate-winter-white/`
- Licence déclarée dans le fichier :
  - CC BY-NC-SA 4.0 pour le style cartographique.
  - La note d’usage commercial renvoie à BY-ND 4.0.
- Références d’origine :
  - https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme
  - https://www.openandromaps.org/en/license

Important :
- `Elevate.xml` renvoie à `read_me_elevate.txt` pour les licences de réutilisation des ressources.
- Les copies versionnées incluent `read_me_elevate.txt` ; les compilations ordinaires ne téléchargent pas les ressources des thèmes.
- Le fichier source de secours racine conserve `app/src/main/assets/read_me_elevate.txt` à côté d’`Elevate.xml`.
- La page de licence d’OpenAndroMaps impose des conditions d’intégration/autorisation.
- Le responsable du projet a indiqué avoir reçu l’accord d’OpenAndroMaps et de Tobias Kuehn pour l’utilisation/intégration dans GlanceMap.
- Les dates exactes et le périmètre autorisé doivent être consignés hors du dépôt public avant son ouverture.

## 3) Thème Mapsforge OpenHiking

- Fichiers intégrés :
  - `app/src/main/assets/theme/openhiking/OpenHiking.xml`
  - arborescence `app/src/main/assets/theme/openhiking/`
- Version d’origine figée dans la configuration :
  - `2026-02-10`
- Archive facultative d’actualisation à la compilation :
  - `https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus`
- Contenu extrait observé le 2026-03-14 :
  - `OpenHiking/OpenHiking.xml`
  - sous-dossiers SVG/motifs/symboles inclus dans le ZIP.
- Références d’origine :
  - https://openhiking.eu/en/downloads/mapsforge-maps
  - https://openhiking.eu/locus/Hungary.xml

Important :
- Le ZIP téléchargé ne contenait pas de fichier de licence/readme distinct clairement identifiable.
- Le responsable du projet a confirmé le 2026-04-26 avoir reçu l’accord d’OpenHiking.
- Il a confirmé la licence `CC BY-SA` du thème.
- Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
- Archiver en privé la page/version exacte et les détails de l’accord lorsqu’ils sont disponibles.
- Note utilisateur : `licenses/OPENHIKING_THEME.md`.

## 4) Thème Mapsforge Tiramisu

- Fichiers intégrés :
  - `app/src/main/assets/theme/tiramisu/Tiramisu.xml`
  - arborescence `app/src/main/assets/theme/tiramisu/res/`
- Source de la copie incluse :
  - https://github.com/IgorMagellan/Tiramisu/releases/tag/v4.4
- Contenu observé le 2026-03-18 :
  - `Tiramisu.xml`
  - arborescence SVG `res/`
  - `LICENSE`
  - `README.md`
- Licence déclarée :
  - CC BY-NC-SA 3.0

Important :
- La licence d’origine est non commerciale.
- Le responsable du projet a confirmé le 2026-04-26 le modèle non commercial de GlanceMap ; le dépôt public et le projet d’appli actuels sont donc considérés comme compatibles.
- Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
- Réexaminer en cas de passage à un modèle commercial.
- Note utilisateur : `licenses/TIRAMISU_THEME.md`.

## 5) Thème Mapsforge French Kiss

- Fichiers intégrés :
  - `app/src/main/assets/theme/frenchkiss/frenchkiss.xml`
  - `app/src/main/assets/theme/frenchkiss/motifs/`
- Source de la copie locale actuelle :
  - `xctrack-beta-latest.apk` fourni par l’utilisateur
- Contenu observé le 2026-03-18 :
  - `frenchkiss.xml`
  - arborescence PNG `motifs/`
- Provenance déclarée dans l’en-tête XML :
  - `IGN TOP25 theme for XCTrack by Pascal Cochet`

Important :
- Aucun fichier de licence distinct n’a été observé à côté des ressources extraites.
- Le responsable du projet a confirmé le 2026-04-27 avoir reçu l’accord du développeur de French Kiss.
- Conserver l’attribution actuelle dans les crédits, sauf demande ultérieure d’un libellé plus précis par l’auteur.
- Archiver en privé les détails exacts de l’accord.
- Note utilisateur : `licenses/FRENCH_KISS_THEME.md`.

## 6) Thème Mapsforge Hike, Ride & Sight

- Fichiers intégrés :
  - `app/src/main/assets/theme/hike-ride-sight/HikeRideSight.xml`
  - `app/src/main/assets/theme/hike-ride-sight/PATTERNS/`
  - `app/src/main/assets/theme/hike-ride-sight/SYMBOLS/`
- Page du thème :
  - http://j.seydoux.free.fr/locus/hrs.html
- Téléchargement manuel du ZIP :
  - http://j.seydoux.free.fr/locus/Hike,%20Ride%20&%20Sight!.zip
- Licence observée :
  - CC BY-NC-SA 3.0 déclarée dans le commentaire d’en-tête XML

Important :
- Aucun fichier de licence distinct n’a été observé dans le ZIP.
- La licence déclarée est non commerciale.
- Le responsable du projet a confirmé le 2026-04-26 avoir reçu l’accord d’utilisation/redistribution par GlanceMap.
- Le modèle non commercial actuel de GlanceMap est considéré comme compatible.
- Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
- Les liens utilisateur requis figurent dans `licenses/CREDITS_AND_THANKS.md`.
- Note utilisateur : `licenses/HIKE_RIDE_SIGHT_THEME.md`.

## 7) Thème Mapsforge Voluntary

- Fichiers intégrés :
  - `app/src/main/assets/theme/voluntary/Voluntary V5.xml`
  - `app/src/main/assets/theme/voluntary/vol_res/`
- Site :
  - https://voluntary.nichesite.org/
- Téléchargement manuel du ZIP :
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20MF5.zip
- Légende :
  - https://voluntary.nichesite.org/key.html
  - https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/themes/voluntary/downloads/Voluntary%20Key.pdf
- Licence observée :
  - CC BY-NC-SA 3.0 déclarée dans le commentaire d’en-tête XML et la page de crédits d’origine

Important :
- Aucun fichier de licence distinct n’a été observé dans le ZIP.
- La licence déclarée est non commerciale.
- Le responsable du projet a confirmé le 2026-04-29 avoir reçu l’accord du créateur pour l’intégration dans GlanceMap.
- Le modèle non commercial actuel de GlanceMap est considéré comme compatible.
- Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
- Note utilisateur : `licenses/VOLUNTARY_THEME.md`.

## 8) Thème Mapsforge OS Map

- Fichiers intégrés :
  - `app/src/main/assets/theme/os-map/OS Map V4 Day.xml`
  - `app/src/main/assets/theme/os-map/OS Map V4 Night.xml`
  - `app/src/main/assets/theme/os-map/os_res/`
- Discussion :
  - https://forum.locusmap.eu/index.php?topic=7000.msg59948#msg59948
- ZIP de version :
  - https://drive.google.com/uc?export=download&id=1RzZTMLRIQM1pY_R8FXdVxKPSTqO2LHUE
- Légendes :
  - https://drive.google.com/file/d/1PE0eBzJnGMbDs9a_V_uhQQa0db5RK-Zs/view
  - https://drive.google.com/file/d/1OwAeuBtYN-XxjGkpOs3SrdYAUwYXDets/view
- Licence observée :
  - CC BY-NC-SA 3.0 déclarée dans le commentaire d’en-tête XML

Important :
- La licence déclarée est non commerciale.
- Le modèle non commercial actuel de GlanceMap est considéré comme compatible.
- Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
- Note utilisateur : `licenses/OS_MAP_THEME.md`.

## 9) Imports Refuges.info

- Le compagnon importe les POI depuis :
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`
- Référence de licence :
  - https://www.refuges.info/wiki/licence

Notes :
- Refuges.info indique que le contenu des pages de points est sous CC BY-SA.
- Refuges.info renvoie aussi aux données OpenStreetMap et aux obligations ODbL lorsqu’elles s’appliquent.

## 9) Enrichissement des POI OSM via Overpass API

- Point d’accès du compagnon :
  - `https://overpass-api.de/api/interpreter`
- Documentation :
  - https://wiki.openstreetmap.org/wiki/Overpass_API

Note d’usage :
- Les recommandations de l’instance publique indiquent des limites prudentes de requêtes/jour et de bande passante/jour.

## 10) Données d’altitude DEM (rendu des pentes/du relief)

- La compilation et l’exécution utilisent des tuiles `.hgt.zip` de type DEM3 et, en option, des tuiles `.hgt.gz` à 1 seconde d’arc.
- Source par défaut dans l’appli/la chaîne de compilation :
  - `https://download.mapsforge.org/maps/dem/dem3`
- Source facultative à 1 seconde d’arc :
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`
- Références :
  - https://download.mapsforge.org/maps/dem/
  - https://download.mapsforge.org/maps/dem/ReadMe.md
  - https://www.mapzen.com/blog/terrain-tile-service/
  - https://www.mapzen.com/rights/

## 11) Icônes POI

- Les icônes POI locales dérivent des ensembles SVG Maki et Temaki :
  - https://github.com/mapbox/maki
  - https://github.com/rapideditor/temaki
- La compilation peut aussi télécharger des icônes SVG Maki de secours depuis :
  - https://github.com/mapbox/maki
- Licence :
  - CC0 1.0 Universal
  - https://creativecommons.org/publicdomain/zero/1.0/

## 12) Ressources et segments d’itinéraire BRouter

- Profils de calcul intégrés :
  - `app/src/main/assets/brouter/profiles2/lookups.dat`
  - `app/src/main/assets/brouter/profiles2/hiking-mountain.brf`
- Source de téléchargement des segments dans le compagnon :
  - `https://brouter.de/brouter/segments4`
- Références d’origine :
  - https://github.com/abrensch/brouter
  - https://brouter.de/brouter/

Notes :
- BRouter indique que les segments `.rd5` sont générés à partir d’OpenStreetMap et mis à jour chaque semaine.
- L’attribution OpenStreetMap reste pertinente pour les données de segments.
- Le code BRouter est intégré séparément sous licence MIT dans l’appli montre ; voir la mention de licence dans `THIRD_PARTY_NOTICES.md`.

## 13) Fichiers importés par l’utilisateur

- L’appli accepte les fichiers de cartes/POI/GPX importés par l’utilisateur.
- Leurs licences et droits de redistribution restent de sa responsabilité.

## 14) Liste de contrôle pratique

- Garder un écran légal accessible depuis les paramètres de la montre.
- Garder `CREDITS_AND_THANKS.md` court et accessible.
- Synchroniser les documents juridiques détaillés avec les liens et API du compagnon.
- Revérifier les conditions à chaque changement d’URL de fournisseur ou de source d’import.

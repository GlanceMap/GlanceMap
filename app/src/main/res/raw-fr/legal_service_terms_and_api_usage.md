# Conditions des services et usage des API

Dernière révision : 2026-05-06

Ce document consigne les contraintes juridiques et d’usage hors bibliothèques des services et fournisseurs externes référencés par l’appli/la chaîne de compilation.

## 1) OpenAndroMaps et Elevate

Usages :
- Recommandations de cartes à télécharger dans le compagnon.
- Téléchargement du thème Elevate et ressources locales de secours à la compilation/exécution sur la montre.

Références :
- https://www.openandromaps.org/en/license
- https://www.openandromaps.org/en/legend/elevate-mountain-hike-theme

Points importants de la page de licence d’origine :
- Les liens directs vers les cartes sont indiqués comme interdits.
- Vendre des cartes ou les intégrer à une appli nécessite l’accord écrit du fournisseur.
- L’intégration des thèmes « andromaps_ » est généralement autorisée pour les services gratuits de mise à jour.
- L’intégration des thèmes de Tobias, dont Elevate, nécessite son accord écrit.

État du projet :
- Le responsable a indiqué avoir reçu l’accord d’OpenAndroMaps et de Tobias Kuehn pour l’utilisation/intégration dans GlanceMap.
- Conserver les messages d’accord d’origine hors du dépôt public.
- Avant de rendre GitHub public, consigner en privé les dates de demande/réponse et le périmètre autorisé dans les notes de publication ou un ticket interne.

## 2) Thème Mapsforge OpenHiking

Usages :
- Ressources locales du thème OpenHiking intégrées dans l’appli montre.
- Téléchargement facultatif d’actualisation du thème à la compilation.
- Référence au site de cartes/thèmes OpenHiking dans le compagnon.

Références :
- https://openhiking.eu/en/downloads/mapsforge-maps
- https://openhiking.eu/en/component/phocadownload/category/1-terkepek/6-mapsforge-stilus?Itemid=102&download=14:openhiking-terkep-stilus

Conditions :
- Le site propose des liens directs de téléchargement/installation, mais aucune API généraliste documentée n’a été trouvée.
- Le ZIP du thème observé le 2026-03-14 ne contenait pas de fichier de licence/readme distinct clairement identifiable.

État du projet :
- Le responsable a confirmé le 2026-04-26 avoir reçu l’accord d’OpenHiking.
- Il a confirmé la licence `CC BY-SA` du thème.
- Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
- Consigner les détails de la copie actuelle dans `licenses/OPENHIKING_THEME.md`.

## 3) Thème Mapsforge Voluntary

Usages :
- Ressources locales du thème Voluntary intégrées dans l’appli montre.
- Références au site et à la légende dans le compagnon.

Références :
- https://voluntary.nichesite.org/
- https://voluntary.nichesite.org/downloads.html
- https://voluntary.nichesite.org/key.html
- https://voluntary.nichesite.org/credits.html

Conditions :
- Le site propose des liens manuels directs de téléchargement et de légende, mais aucune API généraliste documentée n’a été trouvée.
- Le ZIP du thème observé le 2026-04-29 ne contenait pas de fichier de licence/readme distinct clairement identifiable.
- L’en-tête XML et les crédits indiquent CC BY-NC-SA 3.0.

État du projet :
- Le responsable a confirmé le 2026-04-29 avoir reçu l’accord du créateur pour l’intégration dans GlanceMap.
- Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
- Consigner les détails de la copie actuelle dans `licenses/VOLUNTARY_THEME.md`.

## 4) API et données Refuges.info

Usages :
- Requêtes du compagnon vers :
  - `https://www.refuges.info/api/bbox`
  - `https://www.refuges.info/api/polygones`

Références :
- Racine de l’API : https://www.refuges.info/api
- Licence : https://www.refuges.info/wiki/licence

Conditions :
- Le contenu des pages de points Refuges.info est publié sous Creative Commons BY-SA.
- Refuges.info réutilise aussi les données OpenStreetMap sous ODbL lorsqu’elle s’applique.

## 5) OpenStreetMap et Overpass

Usages :
- Attribution OSM dans l’appli.
- Tuiles raster de fond du sélecteur cartographique du compagnon :
  - `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
- Enrichissement des POI OSM via :
  - `https://overpass-api.de/api/interpreter`

Références :
- Droits d’auteur et attribution OSM : https://www.openstreetmap.org/copyright
- Politique des tuiles OSM : https://operations.osmfoundation.org/policies/tiles/
- ODbL : https://opendatacommons.org/licenses/odbl/1-0/
- Documentation Overpass API : https://wiki.openstreetmap.org/wiki/Overpass_API

Politique d’usage :
- L’usage des tuiles OSM doit rester limité à la consultation interactive normale. Ne pas ajouter de préchargement, téléchargement massif ou cache hors ligne ciblant `tile.openstreetmap.org`.
- Les tuiles OSM exigent une attribution visible, l’URL HTTPS officielle, un User-Agent clair pour l’appli et un cache HTTP respectant les en-têtes du serveur.
- Les recommandations de l’instance publique Overpass indiquent un usage prudent d’environ :
  - moins de 10 000 requêtes/jour
  - moins de 1 GB téléchargé/jour

## 6) Moteur et segments de calcul BRouter

Usages :
- Calcul d’itinéraire hors ligne sur la montre via les modules BRouter intégrés.
- Téléchargement par le compagnon de segments `.rd5` depuis :
  - `https://brouter.de/brouter/segments4`

Références :
- Projet : https://github.com/abrensch/brouter
- Site officiel : https://brouter.de/brouter/
- Dossier des segments : https://brouter.de/brouter/segments4/

Conditions et attribution :
- Le projet d’origine indique que BRouter est sous licence MIT.
- Il indique que les segments `.rd5` sont générés à partir d’OpenStreetMap et mis à jour chaque semaine.
- L’attribution OpenStreetMap et le cadre ODbL restent donc pertinents pour les données d’itinéraire BRouter.

Note du projet :
- Aucune page publique distincte de politique de service ou de limites de débit n’a été identifiée dans les pages BRouter examinées le 2026-03-20.
- Déduction du projet : garder des téléchargements prudents dans le compagnon et revérifier les conditions avant tout usage automatisé à grande échelle.

## 7) Sources DEM et de relief

Usages :
- Téléchargements DEM3 depuis :
  - `https://download.mapsforge.org/maps/dem/dem3`
- Téléchargements facultatifs de DEM à 1 seconde d’arc depuis :
  - Mapzen Terrain Tiles / AWS `skadi`
  - `https://s3.amazonaws.com/elevation-tiles-prod/skadi`

Références :
- https://download.mapsforge.org/maps/dem/
- https://download.mapsforge.org/maps/dem/ReadMe.md
- https://www.mapzen.com/blog/terrain-tile-service/
- https://www.mapzen.com/rights/

## 8) Source d’icônes POI à la compilation

Usage :
- Téléchargement des icônes SVG Maki à la compilation.

Références :
- https://github.com/mapbox/maki
- Licence : CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/)

## 9) Conditions des services Google Play

Usages :
- Dépendances d’exécution `play-services-wearable`, `play-services-location`.

Références :
- Contrat de licence du SDK Android : https://developer.android.com/studio/terms
- Recommandations de mentions libres Google Play services : https://developers.google.com/android/guides/opensource

Note :
- Ce sont les conditions des SDK/services Google, et non des licences libres classiques comme Apache/LGPL.

## 10) Absence d’affiliation

L’appli référence des fournisseurs et sites externes pour faciliter leur accès. Sauf déclaration explicite de ces fournisseurs, GlanceMap n’est ni officiellement affilié à eux ni approuvé par eux.

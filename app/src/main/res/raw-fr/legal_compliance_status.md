# État de conformité

Dernière révision : 2026-05-07

Légende :
- `DONE` : réalisé et vérifié.
- `PENDING` : suivi nécessaire avant/après publication.
- `BLOCKER` : ne pas distribuer une ressource intégrée avant résolution.

## Liste de contrôle actuelle

1. Accès aux mentions légales depuis les paramètres de la montre
   - État : `DONE`
   - Preuve : écran `Settings -> Licenses` disponible dans l’appli.

2. Page de crédits accessible aux utilisateurs
   - État : `DONE`
   - Preuve : `licenses/CREDITS_AND_THANKS.md`.

3. Mentions des bibliothèques libres principales
   - État : `DONE`
   - Preuve : `licenses/THIRD_PARTY_NOTICES.md`.

4. Attribution des données et ressources
   - État : `DONE`
   - Preuve : `licenses/DATA_AND_ASSET_ATTRIBUTION.md`.

5. Page des conditions des services/API
   - État : `DONE`
   - Preuve : `licenses/SERVICE_TERMS_AND_API_USAGE.md`.

6. Transparence sur les sources externes du compagnon
   - État : `DONE`
   - Preuve : `licenses/COMPANION_EXTERNAL_SOURCES.md`.

7. Autorisation de redistribution d’OpenAndroMaps/Elevate
   - État : `DONE`
   - Preuve :
     - Le responsable du projet a confirmé le 2026-04-20 l’accord d’OpenAndroMaps et de Tobias Kuehn pour l’utilisation/intégration dans GlanceMap.
   - Notes :
     - Conserver les messages d’accord d’origine hors du dépôt public.
     - Avant de rendre le dépôt public, consigner en privé les dates de demande/réponse et le périmètre autorisé dans les notes de publication ou un ticket interne.
     - Le périmètre doit couvrir la redistribution publique des sources, l’intégration APK/AAB et toute adaptation du thème ou des ressources Elevate utilisée par GlanceMap.

8. Fichier de licence des ressources référencé par Elevate (`read_me_elevate.txt`)
   - État : `DONE`
   - Preuve :
     - `app/src/main/assets/read_me_elevate.txt` est versionné à côté du fichier source de secours `Elevate.xml`.
   - Notes :
     - Référencé par `app/src/main/assets/Elevate.xml`.
     - Les copies Elevate versionnées incluent `read_me_elevate.txt` ; les compilations ordinaires intègrent ces ressources locales sans télécharger les thèmes.
     - Le fichier source de secours versionné préserve les informations de licence du thème et des ressources nécessaires à l’examen public des sources.

9. Limites d’usage de l’instance publique Overpass
   - État : `PENDING`
   - Notes :
     - La politique publique principale indique des limites prudentes (10 000 requêtes/jour, <1 GB/jour).
     - La surveillance et la limitation des imports doivent rester prudentes.

10. Vérification de la redistribution/licence du thème OpenHiking intégré
   - État : `DONE`
   - Preuve :
     - Le responsable du projet a confirmé le 2026-04-26 avoir reçu l’accord d’OpenHiking.
     - Le responsable du projet a confirmé la licence `CC BY-SA` du thème.
   - Notes :
     - La copie OpenHiking est intégrée dans `app/src/main/assets/theme/openhiking`.
     - L’intégration du ZIP OpenHiking reste une voie facultative d’actualisation dans la chaîne de compilation.
     - Le ZIP observé ne contenait pas de fichier de licence/readme distinct clairement identifiable.
     - Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
     - Archiver en privé la page/version d’origine exacte et les détails de l’accord lorsqu’ils sont disponibles.

11. Vérification de la redistribution/licence du thème French Kiss intégré
   - État : `DONE`
   - Preuve :
     - La copie French Kiss est intégrée dans `app/src/main/assets/theme/frenchkiss`.
     - Elle a été extraite de l’APK bêta XCTrack fourni par l’utilisateur.
     - Le XML intégré l’identifie comme thème IGN TOP25 pour XCTrack par Pascal Cochet.
     - Le responsable du projet a confirmé le 2026-04-27 avoir reçu l’accord du développeur de French Kiss.
   - Notes :
     - Aucun fichier de licence distinct n’a été observé à côté des ressources.
     - Conserver l’attribution actuelle dans les crédits, sauf demande ultérieure d’un libellé plus précis par l’auteur.
     - Conserver le message d’accord d’origine hors du dépôt public et archiver en privé les dates de demande/réponse et le périmètre autorisé.

12. Examen de la licence non commerciale du thème Tiramisu intégré
   - État : `DONE`
   - Preuve :
     - La copie Tiramisu est intégrée dans `app/src/main/assets/theme/tiramisu`.
     - L’archive observée contient une licence CC BY-NC-SA 3.0.
     - Le responsable du projet a confirmé le 2026-04-26 le modèle non commercial de GlanceMap.
   - Notes :
     - Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
     - Réexaminer avant tout passage à un modèle commercial.

13. Examen de la licence non commerciale du thème Hike, Ride & Sight intégré
   - État : `DONE`
   - Preuve :
     - La copie Hike, Ride & Sight est intégrée dans `app/src/main/assets/theme/hike-ride-sight`.
     - L’en-tête XML observé déclare CC BY-NC-SA 3.0.
     - Le responsable du projet a confirmé le 2026-04-26 avoir reçu l’accord d’utilisation/redistribution par GlanceMap.
     - Les liens de crédit requis figurent dans `licenses/CREDITS_AND_THANKS.md`.
   - Notes :
     - Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
     - Réexaminer avant tout passage à un modèle commercial.

14. Examen de la licence non commerciale du thème Voluntary intégré
   - État : `DONE`
   - Preuve :
     - La copie Voluntary est intégrée dans `app/src/main/assets/theme/voluntary`.
     - L’en-tête XML observé et la page de crédits d’origine déclarent CC BY-NC-SA 3.0.
     - Le responsable du projet a confirmé le 2026-04-29 avoir reçu l’accord du créateur pour l’intégration dans GlanceMap.
     - Les liens de légende et de référence figurent dans le compagnon.
   - Notes :
     - Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
     - Conserver le message d’accord d’origine hors du dépôt public et archiver en privé les dates de demande/réponse et le périmètre autorisé.
     - Réexaminer avant tout passage à un modèle commercial.

15. Examen de la licence non commerciale du thème OS Map intégré
   - État : `DONE`
   - Preuve :
     - Les copies jour/nuit d’OS Map V4 sont intégrées dans `app/src/main/assets/theme/os-map`.
     - L’en-tête XML observé déclare CC BY-NC-SA 3.0.
     - Les liens de légende et de discussion figurent dans le compagnon.
   - Notes :
     - Conserver les obligations d’attribution et de partage à l’identique dans la documentation.
     - Réexaminer avant tout passage à un modèle commercial.

## Conditions de publication du dépôt et de l’appli

Pour un dépôt ou une appli publics, résoudre tous les points `BLOCKER`.
Traiter les points `PENDING` non résolus de redistribution/licence des ressources intégrées comme bloquants, sauf retrait des ressources concernées de la branche publique ou acceptation explicite du risque résiduel. Rendre le dépôt public publie aussi les ressources tierces intégrées.

Résultat actuel :
- `READY FROM A BUNDLED-ASSET LICENSING STANDPOINT`, sans blocage de redistribution encore consigné pour l’ensemble actuel de thèmes.
- L’autorisation OpenAndroMaps/Elevate et les preuves de licence/readme des sources ne sont plus bloquantes, sur la base de la confirmation du responsable et du readme source de secours versionné.
- OpenHiking, French Kiss, Tiramisu, Hike, Ride & Sight, Voluntary et OS Map ne sont plus bloquants, sur la base des confirmations consignées lorsqu’elles s’appliquent et du modèle non commercial actuel lorsqu’il est pertinent.
- Les tâches restantes avant publication sont opérationnelles, dans `docs/PUBLIC_REPO_CHECKLIST.md`, notamment les métadonnées GitHub, l’activation de Pages et le signalement de sécurité.

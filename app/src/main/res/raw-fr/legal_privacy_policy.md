# Politique de confidentialité de GlanceMap

Dernière mise à jour : 2026-05-31

Cette politique s’applique à GlanceMap, notamment à l’appli montre Wear OS et à son compagnon Android, distribués sous le nom GlanceMap et le paquet `com.glancemap.glancemapwearos`.

Contact pour la confidentialité : `Glancemap@protonmail.com`

## Présentation

GlanceMap est conçu pour fonctionner principalement sur vos appareils. L’essentiel de la navigation, du rendu cartographique, du stockage des fichiers, des réglages et des transferts téléphone/montre s’effectue localement sur la montre et le téléphone.

Aucun compte n’est requis. Nous ne vendons pas vos données personnelles. Nous n’utilisons pas votre position ni vos fichiers à des fins publicitaires.

## Données consultées et finalités

### 1. Position sur la montre et dans l’appli compagnon

- Position précise ou approximative, avec votre autorisation.
- Utilisée pour la navigation en direct, le centrage de la carte, le guidage, l’estimation d’arrivée, les fonctions de boussole/localisation et les fonctions GPS associées au premier plan.
- Si vous utilisez le suivi en direct dans le compagnon, la position du téléphone est envoyée à Arkluz pour publier votre trace en direct dans le groupe configuré.
- Dans la configuration actuellement examinée, GlanceMap ne demande pas `ACCESS_BACKGROUND_LOCATION`. L’autorisation de localisation est demandée pour un usage au premier plan.

### 2. Fichiers et contenu utilisateur

- Fichiers que vous choisissez d’importer, ouvrir, générer, stocker, transférer ou exporter, notamment `.gpx`, `.map`, `.poi`, `.rd5` et les fichiers de diagnostic.
- Utilisés pour afficher les cartes, itinéraires, points d’intérêt et données de calcul, effectuer les transferts téléphone/montre et les exports facultatifs de diagnostic.
- Ces fichiers peuvent contenir des informations personnelles ou sensibles selon ce que vous y placez.

### 3. Informations sur l’appareil, l’appli et les connexions

- Version de l’appli, modèle d’appareil, version Android/Wear OS, état des notifications et des autorisations de localisation/Bluetooth/notifications, état Bluetooth/Wi-Fi/réseau, informations sur les montres proches, état des transferts, identifiants des montres choisies et métadonnées locales des fichiers, comme les noms et tailles.
- Utilisés pour l’association, les transferts, la compatibilité, les notifications, le débogage et les performances.

### 4. Diagnostic et plantages

- L’appli peut stocker sur l’appareil des notes de plantage et des journaux de diagnostic locaux.
- Si vous exportez manuellement un diagnostic, le rapport peut contenir des détails sur l’appareil et l’appli, des lignes de journal, des traces de pile de plantage, des informations de transfert, des horodatages, certains noms de fichiers et, si la capture du diagnostic est activée, des données de télémétrie de localisation ou GNSS.
- Ces diagnostics servent au dépannage et ne quittent l’appareil que si vous choisissez explicitement de les envoyer ou de les exporter.

### 5. Réglages et préférences locales

- Préférences telles que les unités, l’intervalle GPS, les options de carte, les derniers paramètres d’import ou de téléchargement, l’historique des transferts et les thèmes choisis.
- Réglages de suivi en direct : nom du groupe, mots de passe de participation et de consultation, nom du participant, adresses e-mail facultatives de notification et d’alerte, intervalle d’alerte d’immobilité, brouillons de commentaires et référence GPX choisie.
- Utilisés pour mémoriser votre configuration et faciliter l’usage entre les lancements.

## Quand les données quittent votre appareil

Les données peuvent quitter votre appareil dans les cas suivants :

### 1. Téléchargements demandés à des fournisseurs tiers

Lorsque vous demandez des POI en ligne, des données d’itinéraire ou d’altitude, des aperçus du sélecteur de carte ou des données similaires, l’appli transmet au fournisseur choisi les informations nécessaires : zone géographique sélectionnée (rectangle englobant), noms des tuiles de calcul demandées, coordonnées des tuiles affichées ou fichiers demandés.

Comme pour toute requête Internet ordinaire, ces fournisseurs peuvent aussi recevoir les métadonnées usuelles : adresse IP, heure de la requête, agent utilisateur ou informations de connexion similaires.

La configuration actuelle utilise notamment :

- `refuges.info`
- `overpass-api.de`
- `tile.openstreetmap.org`
- `brouter.de`
- `download.mapsforge.org`
- `arkluz.com`

Si vous ouvrez des sites tiers de téléchargement de cartes ou GPX depuis le compagnon, leurs propres politiques de confidentialité s’appliquent.

### 2. Suivi en direct facultatif via Arkluz

Lorsque vous lancez le suivi en direct dans le compagnon, l’appli envoie à Arkluz la position GPS, l’horodatage, la précision, éventuellement l’altitude et la vitesse, le niveau de batterie, le nom du groupe, le mot de passe de participation, le nom du participant, les adresses e-mail facultatives de notification et d’alerte, ainsi que les réglages d’alerte d’immobilité.

Si vous ajoutez un itinéraire GPX ou un commentaire, ils sont aussi transmis à Arkluz pour le groupe de suivi. En cas d’échec réseau, l’appli peut conserver temporairement des points GPS en attente sur l’appareil et retenter leur envoi. Les traces et positions de suivi devraient être supprimées automatiquement par Arkluz après 7 jours.

### 3. Transfert de fichiers téléphone/montre

Les fichiers choisis sont transférés entre votre téléphone et votre montre via les canaux de communication Google Wear OS et, dans certains cas, via un transfert HTTP Wi-Fi local protégé par un jeton sur votre réseau local.

Nous n’exploitons pas de service cloud dédié à ces transferts.

### 4. Export du diagnostic à votre initiative

Si vous exportez ou envoyez un diagnostic par e-mail, le contenu choisi est joint à un brouillon ou partagé via l’appli de votre choix. Votre fournisseur de messagerie ou l’autre destinataire choisi gère ensuite la transmission selon ses propres règles.

### 5. Services Firebase facultatifs, s’ils sont configurés dans une version publiée

Le code de GlanceMap prévoit une intégration future facultative de Firebase Analytics et Firebase Crashlytics.

Dans la configuration actuellement examinée, Firebase n’est pas activé et GlanceMap ne lui envoie ni données d’analyse ni rapports de plantage.

Si Firebase est activé dans une version future, Google ou Firebase pourra traiter des événements d’analyse, diagnostics de plantage, métadonnées d’appareil ou d’appli et informations de performances associées, selon les conditions et pratiques de confidentialité de Google. Le formulaire Sécurité des données de Google Play et cette politique devront alors être réexaminés avant publication.

## Partage

Nous pouvons partager des données uniquement si nécessaire aux fonctions que vous choisissez :

- Avec les composants de communication Wear OS / services Google Play qui relient le téléphone et la montre et transfèrent leurs données.
- Avec les fournisseurs tiers auxquels vous faites directement appel pour télécharger cartes, POI, itinéraires ou relief.
- Avec Arkluz si vous utilisez le suivi en direct facultatif.
- Avec votre appli d’e-mail ou de partage et son fournisseur lorsque vous envoyez manuellement un diagnostic ou des fichiers exportés.
- Avec Firebase ou Google uniquement si Firebase est activé dans la version installée.

Nous ne vendons pas vos données personnelles.

## Stockage, conservation et suppression

- Cartes importées, fichiers GPX, bases POI, packs d’itinéraires, réglages et données de travail similaires sont généralement conservés localement jusqu’à leur suppression, l’effacement des données de l’appli ou sa désinstallation.
- Les caches temporaires peuvent être supprimés automatiquement ou via les actions de nettoyage intégrées.
- L’historique des transferts, les derniers paramètres de téléchargement/import et l’état de l’aide peuvent rester sur l’appareil jusqu’à leur effacement, remplacement ou suppression avec les données de l’appli.
- Réglages de groupe de suivi, mots de passe, réglages d’e-mail, brouillons de commentaires, référence GPX et positions en attente de renvoi peuvent rester sur l’appareil jusqu’à leur effacement, remplacement, envoi, suppression par déconnexion, nettoyage ou suppression avec les données de l’appli.
- Les fichiers locaux de plantage et de diagnostic peuvent rester jusqu’à leur remplacement, effacement ou à la désinstallation.
- Les fichiers de diagnostic préparés pour l’e-mail peuvent rester dans le cache jusqu’à leur nettoyage par l’appli, le système ou vous-même.
- Aucun compte n’étant requis, il n’existe pas de profil cloud lié à un compte à supprimer.

## Sécurité

- L’appli stocke surtout ses données dans des espaces privés et utilise Android `FileProvider` pour le partage contrôlé de fichiers si nécessaire.
- Les requêtes aux fournisseurs tiers utilisent généralement HTTPS dans la configuration actuelle.
- Le suivi en direct vers Arkluz utilise HTTPS dans la configuration de publication.
- Le transfert Wi-Fi local téléphone/montre utilise actuellement une connexion HTTP locale protégée par un jeton sur votre réseau ; il ne doit pas être considéré comme un stockage Internet chiffré de bout en bout.
- Aucune méthode de stockage ou de transmission n’est parfaitement sûre ; les services ou applis tiers choisis ont leurs propres pratiques de sécurité.

## Vos choix

Vous pouvez :

- Accorder ou refuser les autorisations de localisation, de notifications ou de Bluetooth.
- Choisir les fichiers à importer, générer, transférer ou exporter.
- Choisir de créer ou rejoindre un groupe de suivi, lancer/arrêter le suivi, ajouter des GPX/commentaires ou configurer les e-mails de notification et d’alerte.
- Choisir de lancer une capture de diagnostic ou d’envoyer un diagnostic par e-mail.
- Gérer ou supprimer les fichiers locaux, caches et données depuis l’appli ou les paramètres Android.
- Désinstaller l’appli pour supprimer ses données locales sur cet appareil, sous réserve du fonctionnement habituel d’Android et des fichiers exportés ailleurs.

## Enfants

GlanceMap n’est pas conçu comme une appli pour enfants.

## Évolution de cette politique

Nous pouvons actualiser cette politique selon l’évolution de l’appli. La date « Dernière mise à jour » sera modifiée en cas de changement important.

## Contact

Pour toute question ou demande sur la confidentialité :

`Glancemap@protonmail.com`

# Redémarrage individuel ou groupé

Le bouton « ↻ Redémarrer », en haut à gauche au-dessus du volume, ouvre la liste
HEOS du réseau. Choisir un appareil ou « Tous les appareils », puis confirmer.
Une recherche SSDP ACT inclut les enceintes surround et le caisson, absents de
`player/get_players`. La liste indique noms, modèles et IP pour éviter les ambiguïtés.
« Tous » signifie tous les appareils de cette liste, y compris ceux d'autres pièces.
Les appareils éteints, injoignables ou sur un autre réseau peuvent manquer : vérifier
la liste et utiliser « Actualiser » avant de confirmer.

Chaque appareil reçoit `heos://system/reboot` sur sa propre connexion TCP 1255.
Les commandes d'un lot partent simultanément, sans attendre le retour de la barre.
Aucun changement de niveaux, reset usine, ni nouvelle tentative automatique.
Une réponse HEOS de succès signifie « commande acceptée », pas « appareil revenu ».
Une fermeture de socket ou un délai dépassé laisse le résultat non confirmé :
la commande peut avoir été appliquée. Chaque résultat est affiché séparément et
les boutons sont bloqués 30 secondes après le lot pour éviter les doubles envois.
La reconnexion existante des commandes et événements de la barre reste active.

Protocole : section « HEOS Speaker Reboot » de la spécification Denon :
https://assets.denon.com/DocumentMaster/us/HEOS_CLI_PROTOCOL_Specification_290616.pdf

## Validation sans redémarrer le matériel

- `cargo test` : faux serveur, acquittement et coupure de connexion, aucun rejeu.
- `cargo run -p heos_core --example discover -- 192.168.1.12` : découverte en lecture seule.
- `scripts/build-android.sh` : reconstruire les bibliothèques ET les bindings UniFFI.
- `cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug`.

Le contrôle physique de redémarrage, le retour en ligne et le rendu sur téléphone
restent à valider par l'utilisateur ; aucun reboot matériel n'est exécuté par les tests.

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
Après un acquittement ou une coupure de transport, l'app observe pendant au plus
2 minutes les réponses `system/heart_beat` sur des connexions neuves. Une coupure
suivie d'une réponse valide affiche « Appareil de nouveau en ligne ». Une simple
connexion TCP ne suffit pas. Sans coupure observée, elle indique que l'appareil
est joignable mais que le reboot n'est pas confirmé ; sans retour, elle indique
que l'appareil reste injoignable. Aucun rejeu du reboot. Une erreur d'ouverture
initiale est indiquée comme commande non envoyée ; un refus explicite n'est pas
traité comme un reboot. Les commandes sont bloquées pendant la vérification.
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

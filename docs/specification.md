# Spécification de la démo HEOS ↔ Android (UniFFI)

_Cahier des charges d'origine, conservé tel quel (hors bruit de copier-coller)._

Pour réaliser cette démo Android, l'architecture repose sur un cœur réseau en Rust (qui communique en TCP/Telnet avec le système Denon HEOS sur le port 1255) et une interface utilisateur en Kotlin (Jetpack Compose) reliés par UniFFI.
Une seule connexion à votre barre de son suffit pour contrôler individuellement toutes les enceintes du réseau grâce à leur identifiant unique (pid).

1. Architecture Globale de l'Application

• Couche Réseau & Métier (Rust) : Gère la découverte réseau (ou connexion IP directe), maintient les sockets TCP (port 1255), formate les commandes ASCII (heos://...) et parse les réponses JSON.
• Pont d'interfaçage (UniFFI) : Expose des structures simples (Speaker), les méthodes d'action (set_volume) et une interface de rappel (Callback Interface) pour notifier Android en temps réel.
• Couche Présentation (Android / Kotlin) : Affiche la liste des enceintes avec des curseurs (Sliders) dans Jetpack Compose et réagit aux changements de volume.

2. Protocole HEOS : Le flux de communication (Côté Rust)

D'après la spécification Denon HEOS CLI, il est recommandé d'ouvrir deux connexions TCP (port 1255) vers l'adresse IP de votre barre de son (une pour envoyer les actions, une dédiée à l'écoute des événements) en suivant cet ordre précis :
1. Connexion & Initialisation :
	• Ouvrir la connexion TCP sur <IP_BARRE_DE_SON>:1255.
	• Désactiver temporairement les événements pour éviter le flot initial : heos://system/register_for_change_events?enable=off\r\n.
2. Récupération des enceintes :
	• Envoyer heos://player/get_players\r\n.
	• Parser le tableau JSON payload pour extraire le name, le model et surtout le pid (Player ID) de chaque enceinte.
3. Récupération de l'état initial des volumes :
	• Pour chaque pid, envoyer heos://player/get_volume?pid=<pid>\r\n.
	• Parser le champ message de la réponse (ex: "pid=1&level=30") pour extraire le volume actuel.
4. Écoute et Contrôle en temps réel :
	• Activer les événements sur la 2e connexion : heos://system/register_for_change_events?enable=on\r\n.
	• Action utilisateur : Quand l'utilisateur bouge un curseur, envoyer heos://player/set_volume?pid=<pid>&level=<0-100>\r\n.
	• Retour d'état : Écouter en boucle les événements event/player_volume_changed (ex: "message": "pid=1&level=35&mute=off") pour mettre à jour l'interface Android si le volume est modifié via la télécommande physique.

3. Conception du contrat UniFFI (Rust \(\leftrightarrow \) Kotlin)

Avec UniFFI (via des macros procédurales #[uniffi::export]), voici les éléments à exposer dans votre crate Rust :
• Modèle de données (Record) :
```rust
#[derive(uniffi::Record)]
pub struct HeosSpeaker {
    pub pid: i64,
    pub name: String,
    pub model: String,
    pub volume: u8,
    pub is_muted: bool,
}
```
• Interface de Callback (Temps réel Rust → Kotlin) :
```rust
#[uniffi::export(callback_interface)]
pub trait VolumeEventListener: Send + Sync {
    fn on_volume_changed(&self, pid: i64, new_level: u8, is_muted: bool);
}
```
• Contrôleur Principal (Object) :
	• connect(ip: String, listener: Box<dyn VolumeEventListener>) -> Result<Vec<HeosSpeaker>, HeosError>
	• set_volume(pid: i64, level: u8) -> Result<(), HeosError>
	• toggle_mute(pid: i64) -> Result<(), HeosError>

4. Plan de développement étape par étape

1. Étape 1 : Créer le moteur Rust (heos_core)
	• Utiliser tokio pour gérer le réseau de façon asynchrone (TCPStream avec lecture ligne par ligne terminée par \r\n).
	• Utiliser serde et serde_json pour désérialiser les réponses JSON de la barre de son.
	• Créer un parseur simple pour le champ message (qui est formaté comme une query URL : clé1=valeur1&clé2=valeur2).
2. Étape 2 : Générer les bindings UniFFI
	• Annoter le code Rust avec les macros uniffi.
	• Compiler la bibliothèque en cibles Android (aarch64-linux-android, etc.) avec cargo-ndk.
	• Générer le fichier Kotlin (heos_core.kt) via la commande uniffi-bindgen.
3. Étape 3 : Développer l'UI Android minimaliste (Démo)
	• Écran 1 (Connexion) : Un champ texte pour entrer l'IP locale de la barre de son (pour éviter de coder la découverte SSDP/UPnP dans un premier temps) et un bouton "Connecter".
	• Écran 2 (Liste des enceintes) : Une LazyColumn en Jetpack Compose affichant chaque HeosSpeaker récupéré. Chaque carte contient le nom de l'enceinte, un bouton Mute et un Slider allant de 0f à 100f.

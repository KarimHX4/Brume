# Brume

**Version web (iPhone, ordinateur) :** https://karimhx4.github.io/Brume/
Sur iPhone : ouvre le lien dans Safari → Partager → « Sur l'écran d'accueil ».

**App Android :** https://github.com/KarimHX4/Brume/releases/download/latest/brume.apk

---

## Brume pour Android

Une carte du monde dans le brouillard, qui se dévoile partout où tu passes, même écran verrouillé.

- Rayon de découverte de 20 m par défaut (15, 20, 25 ou 30 m au choix).
- Aucune limite de vitesse : à pied, en voiture ou en train, le trajet est dévoilé en continu.
- Un % de découverte par commune, calculé sur ses limites officielles OpenStreetMap.
- Import de tracés GPX, et sauvegarde ou restauration en JSON (même format que la version web).

## Obtenir l'APK sans rien installer (GitHub)

1. Crée un dépôt sur github.com (il peut être privé) et envoie-y tout le contenu de ce dossier.
2. Ouvre l'onglet **Actions**. La compilation « Compiler l'APK » démarre toute seule et prend environ 5 min.
3. Clique sur la compilation terminée. Tout en bas, télécharge **brume-apk** (c'est un zip qui contient `app-debug.apk`).
4. Sur le téléphone, ouvre l'APK et autorise l'installation depuis cette source.

L'APK est toujours signé avec la même clé (`app/brume-debug.keystore`). Une nouvelle version s'installe donc par-dessus l'ancienne, sans perdre tes données.

## Avec Android Studio

Ouvre le dossier dans Android Studio, branche le téléphone (mode développeur + débogage USB activés), puis clique sur ▶ Run.

## Pour que le suivi tienne écran verrouillé

- Au premier lancement, accepte **Position précise** et les **notifications**.
- Accepte aussi la demande « ignorer l'optimisation de batterie ». Elle se trouve également dans ⚙ Réglages.
- Sur les Samsung : Paramètres → Batterie → Limites d'utilisation en arrière-plan → ajoute Brume aux « Applis jamais en veille ».
- Sur les Xiaomi : dans les infos de l'appli, mets Économiseur de batterie sur « Pas de restrictions » et active le Démarrage automatique.

Une notification permanente « Brume explore la carte » reste affichée pendant l'exploration. C'est elle qui autorise Android à garder le GPS actif. Tu peux arrêter l'exploration depuis cette notification.

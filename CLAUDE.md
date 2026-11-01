# Télécommande Lampe — instructions pour Claude Code

## Contexte
App Android (Java, sans dépendances AndroidX) qui transforme le Redmi Note 14 Pro+ 5G de Franky
en télécommande infrarouge pour son projecteur **YM-600-SS RGB** (100 W, COB), dont il a perdu la télécommande.
Le récepteur IR de la lampe est la petite bulle noire sur le dessus, près de la poignée.

L'app a deux onglets (tout est dans `app/src/main/java/com/frankystudio/lampe/MainActivity.java`) :
- **Scanner** : balaie les codes NEC (38 kHz) adresse par adresse, en commençant par les adresses courantes
  (0x00, 0x01, 0x80…). Bouton « ÇA A RÉAGI ! » → garde les 4 derniers codes envoyés comme candidats,
  à tester puis assigner à un bouton.
- **Télécommande** : les 11 boutons du manuel (ON, OFF, ☀+/−, K+/−, veilleuse, RGB, R/G/B+),
  codes stockés dans les SharedPreferences.

Le code a déjà été vérifié : il compile contre l'API Android (javac + android.jar).
Franky ne connaît pas le développement mobile : explique simplement, en français, et fais les actions toi-même.

## Ta mission
0. **Avant tout** : le fichier `A_DEPLACER_build.yml` à la racine est le workflow GitHub Actions
   (il n'a pas pu être déposé directement dans `.github`). Déplace-le vers `.github/workflows/build.yml`
   (crée les dossiers), puis supprime `A_DEPLACER_build.yml`.
1. **Publier le projet sur GitHub** dans le dépôt vide déjà créé :
   `https://github.com/dtfedit-creator/telecommande-lampe` (public, branche `main`).
   ```
   git init -b main
   git add -A
   git commit -m "Télécommande IR pour projecteur YM-600-SS"
   git remote add origin https://github.com/dtfedit-creator/telecommande-lampe.git
   git push -u origin main
   ```
   Si le push demande une authentification, utilise `gh auth login` (ou guide Franky pas à pas).
   Ne commite jamais de fichiers de build (`.gitignore` est déjà en place).

2. **Vérifier la compilation** : le push déclenche le workflow `.github/workflows/build.yml`
   (« Construire l'APK »), qui compile `assembleDebug` et publie une Release avec `TelecommandeLampe.apk`.
   Suis-le avec `gh run watch` (ou l'onglet Actions). S'il échoue, lis le log (`gh run view --log-failed`),
   corrige, re-pousse, jusqu'à ce qu'il passe.

3. **Donner le lien de téléchargement à Franky** :
   `https://github.com/dtfedit-creator/telecommande-lampe/releases/latest/download/TelecommandeLampe.apk`
   À ouvrir depuis le téléphone → télécharger → installer (autoriser « installer des applis inconnues »
   pour le navigateur quand Android le demande ; Play Protect peut avertir car l'app n'est pas sur le Play Store :
   « Installer quand même »).
   Option : si ADB est déjà configuré sur le PC (son app « 05 - Debug transfert » l'utilise), tu peux aussi
   installer directement avec `adb install -r TelecommandeLampe.apk` après l'avoir téléchargée.

## Mode d'emploi à expliquer à Franky (une fois l'app installée)
1. Allumer la lampe avec son propre bouton, luminosité moyenne.
2. Onglet **Scanner** → viser la bulle noire du dessus à 1–2 m → **▶ Auto**.
3. Dès que la lampe réagit (s'éteint, change d'intensité, de température ou de couleur) → **ÇA A RÉAGI !**
4. Dans « Codes candidats » : **Tester** chacun, puis **Assigner** le bon au bouton correspondant.
   Une fois un code assigné, l'adresse trouvée est réutilisée : il suffit de continuer avec ◀ / ▶ ou Auto
   pour trouver les autres boutons (ils partagent presque toujours la même adresse).
5. Onglet **Télécommande** pour l'usage normal.

Si aucune réaction après les 12 adresses courantes (~12 min à 250 ms), la lampe n'utilise peut-être pas
le protocole NEC : prochaine étape = ESP32 + récepteur IR, ou une télécommande de remplacement à copier.

## Idées d'évolution (seulement si Franky le demande)
- Presets d'éclairage pour ses tournages (enchaîner plusieurs codes : ex. ON + luminosité ×5 + K−).
- Ajout d'autres protocoles (RC5, Samsung) au scanner si NEC ne donne rien.

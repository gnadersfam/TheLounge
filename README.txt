THE LOUNGE - full version (Live TV, Movies & Shows, Football, Spotify remote, Settings)
=====================================================================================

NO keys or logins are in these files. You type them on your phone ("Pair with phone" in the app);
they are saved only on the Firestick. So it is safe for this to sit in a public GitHub repository.

UPLOAD TO GITHUB (repo gnadersfam/TheLounge)
  1. On the repo page: Add file > Upload files. Drag in everything from this folder EXCEPT the .github folder.
     (Include the "docs" folder.) Commit.
  2. The .github folder must be added by hand (Windows drag-and-drop skips dot-folders):
     Add file > Create new file, name it   .github/workflows/build.yml   and paste the contents of build.yml
     from this folder (replace the old one). Commit.
  3. Actions tab: wait for a green tick (~5 min). If it goes red, copy the red error text to Claude.
  4. Install on the Firestick with Downloader:
     https://github.com/gnadersfam/TheLounge/releases/download/latest/TheLounge.apk
  Later versions: the app shows "Update available" on Home - press it and it installs over the old one.
  (The signing key in the repo is deliberately public; it is not a secret and just lets updates install over each other.)

SPOTIFY (one-time)
  - Needs Spotify Premium and the official Spotify app on the Firestick.
  - Repo Settings > Pages > Branch: main, Folder: /docs > Save.
  - At developer.spotify.com make a free app. Add this Redirect URI exactly:
      https://gnadersfam.github.io/TheLounge/spotify.html
  - In The Lounge > Music follow the on-screen steps (you type the Client ID on your phone).
  - Music may stop when the Firestick leaves the app; background play is not guaranteed.

FOOTBALL
  - Pair your football-data.org key on your phone, then Football > Refresh now (data only updates when you press it).
  - Finding a match's channel needs Live TV opened once so the guide is loaded.

THE LOUNGE - Firestick test version (Live TV + playback only)
==============================================================

What it does
  - Sign in once with your IPTV server / username / password (typed on the Firestick, saved only on the Firestick)
  - English-only Live TV list, same tidy rules as the PC app (no "no event" / Amazon channels, UK regional copies
    hidden except Scotland, UK channels at the top)
  - Highlight a channel to see what's on now / next; press OK to watch
  - While watching: Up / Down = change channel, OK = show what's on, Back = return to the list
  - Reconnects by itself if the stream drops

Your login is NOT in these files, so it is safe for this to sit in a public GitHub repository.

How to get the app onto the Firestick
  1. On a computer, sign in at github.com (free account) -> New repository -> name it TheLounge -> choose PUBLIC -> Create.
  2. On the new repo page click "uploading an existing file". Open this unzipped folder and drag EVERYTHING in it
     (including the .github folder) into the page. Click "Commit changes".
  3. Click the "Actions" tab. A run called "Build The Lounge APK" starts. Wait about 5 minutes for a green tick.
     (If it goes red, click it and copy the error text to Claude.)
  4. The finished app is at:
       https://github.com/YOUR-USERNAME/TheLounge/releases/download/latest/TheLounge.apk
  5. On the Firestick: Settings > My Fire TV > Developer options > Install unknown apps > allow Downloader.
     Open Downloader, type that web address, and install.
  6. Open The Lounge from Your Apps & Channels.

Tip: typing on the Firestick is easiest with the Fire TV phone app's keyboard.

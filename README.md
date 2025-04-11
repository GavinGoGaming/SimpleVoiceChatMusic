# Simple Voice Chat Music Player for Paper

_This mod is a **port** of the [Simple Voice Chat Music](https://modrinth.com/mod/simple-voice-chat-music) fabric mod!_

Enjoy music with your friends on a Paper server. This mod allows you to stream youtube, spotify, soundcloud, bandcamp, vimeo, twitch, mp3, flac, wav, m3u, and more into SimpleVoiceChat groups.
Powered by the lightweight [lavaplayer](https://github.com/lavalink-devs/lavaplayer) library.

![Video example from MOD](https://github.com/ItzDerock/simplevoicechat-music/assets/14848722/c974d7a7-26a7-44b9-9c8a-b6d6722a8582)

## Commands

String args like `<song>` do not need quotes around them due to this version just joining args instead of using brigadier.

- `/music play <song title | spotify URL>` - Searches and queues the first result
- `/music search <song>` - Lists all results from YouTube (default) and lets you choose which you want to queue
- `/music now-playing` - Shows the current song
- `/music queue` - Shows the queue
- `/music skip` - Skips the current song
- `/music pause` - Pauses the current song
- `/music resume` - Resumes the current song
- `/music stop` - Stops the current song and clears the queue
- `/music volume <int;1-100>` - Sets the volume
- `/music kill` - use when something goes wrong and you want to restart the plugin without restarting the server
- `/music bassboost <float;0-200>` - adds bass boost

Song can be a Spotify URL, soundcloud URL, Youtube URL, bandcamp URL, etc or it can be just a search term. By default, it will search on YouTube. This is by query only, for YouTube URL you MUST force it to search on YouTube or Soundcloud etc. by using the query `"ytsearch: (URL)"`, `"scsearch: your search terms"` etc. Lavaplayer also supports YouTube Music, though it wasn't very reliable in my testing. To search YouTube Music, use the query `"ytmsearch: your search terms"`.

### Spotify
Spotify "Integration" does work with this, it uses the Spotify API to get the title and artist and searches it on YouTube. You can paste only spotify URLs that look like `"https://open.spotify.com/track/..."` It does not need a "forced search". It can be pasted in normally like: `/music play https://open.spotify.com/track/19fKJrO9XdOf6Xla2QHecO?si=1f494f7a8d664d62`
## Support
I remade this and added spotify for personal use, so if there are any bugs with it I may or may not be able to assist as more than 90% of this project is NOT my code.
If it has something to do with Spotify you can open an issue otherwise. **I will not force direct you** to him but you may contact @gavingogaming on discord for support with this Paper version - there are bugs in this version nonexistant in the fabric mod.

## Customization

Currently, no options are customizable as this was made for private use, but I thought I'd open-source it since others may find it useful.

## Bugs

This plugin was lightly tested, but heres some bugs I've found so far:
- I (MrTron) only tested Spotify, SoundCloud, and YouTube all seem to be working fine.
- To search a **YouTube URL** you MUST type `ytsearch:(URL)` typing it with `/music play` doesn't work because it checks for either spotify URL or just a youtube search query
- Quotes are not needed around search terms, unlike the original
- /music bassboost errors if no number is given... oop (*I still didnt fix this*)

## Credits

The original mod was created by @ItzDerok - check out the original mod via the link at the top of this readme. I've gotten permission to port the mod to Paper using SVC's bukkit api [here](https://github.com/ItzDerock/simplevoicechat-music/issues/7). As well as credit to @GavinGoGaming for making this fantastic port. 

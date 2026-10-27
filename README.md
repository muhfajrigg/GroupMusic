# GroupMusic

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://adoptium.net/)
[![Platform](https://img.shields.io/badge/Platform-Paper-green.svg)](https://papermc.io/)
[![Simple Voice Chat](https://img.shields.io/badge/Simple%20Voice%20Chat-2.6.0%2B-blueviolet.svg)](https://modrinth.com/plugin/simple-voice-chat)

> **Discord-like music bot for Simple Voice Chat groups on Paper servers.**  
> Developed by **muhfajrigg** ([@muhfajrigg](https://github.com/muhfajrigg)).

*(Baca dokumentasi dalam [Bahasa Indonesia](README_ID.md))*

---

## 🌟 Overview

**GroupMusic** provides isolated, high-fidelity music playback inside Minecraft server voice groups. Players create or join a group in [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (`press V`) and can queue, play, loop, and control music that is heard **only** by members of that group.

Each group has an independent queue and audio stream. Audio is streamed via YouTube with seamless SoundCloud fallback, and metadata can be resolved from Spotify, Apple Music, Deezer, and more.

---

## 🚀 Why GroupMusic?

While other voice chat music plugins exist, most rely on outdated players that easily break when YouTube updates its anti-bot ciphers or require complicated API keys. **GroupMusic was engineered from the ground up to solve these pain points:**

| Feature | Standard SVC Music Plugins | **GroupMusic** |
|---|---|---|
| **YouTube Cipher & SABR Blocks** | ❌ Frequently breaks with 403 Forbidden | ✅ **Remote cipher decoder + automatic SoundCloud fallback** |
| **YouTube URL Search** | ⚠️ Requires awkward ytsearch: prefixes | ✅ **Paste any direct link or title naturally** |
| **Spotify Playlists & Albums** | ❌ Requires Spotify Developer tokens / API setup | ✅ **Built-in public embed scraper (Zero setup required)** |
| **Discovery / Search** | ⚠️ Keyword-only exact match | ✅ **Optional AI Assistant for mood & typo recovery** |
| **Network Security** | ❌ Vulnerable to local network SSRF probes | ✅ **Built-in SSRF protection blocking private IPs** |
| **Thread Safety & TPS** | ⚠️ Can cause main-thread stuttering | ✅ **100% Asynchronous Netty worker audio pipeline** |

---

## ✨ Features

- ⚡️ **Asynchronous & Zero-Lag Architecture:** The entire track search pipeline, YouTube cipher resolution, Spotify embed scraping, and audio streaming execute 100% asynchronously off the main server thread. Audio frames are delivered directly to Simple Voice Chat via dedicated worker threads, ensuring server TPS remains rock-solid at 20.0.
- 🎧 **Per-Group Isolation:** Each voice chat group operates its own music session and queue without overlapping with other groups.
- 🔁 **Loop Modes:** Supports single track loop, full queue loop, and shuffle.
- 🔗 **Smart Link Resolution:**
  - Direct playback: YouTube, SoundCloud, Bandcamp.
  - Web metadata lookup: Spotify, Apple Music, Deezer, TIDAL, Amazon Music, Genius, Shazam.
  - Spotify album & playlist support via official API or public embed parsing (no Spotify API/Premium required).
- 🛡️ **Built-in Resilience & Fallback:**
  - If a YouTube video fails to play (e.g. login or SABR cipher restrictions), GroupMusic automatically cleans the title and finds a replacement on SoundCloud.
  - SSRF protection rejecting non-public IPs, limiting payload sizes, and validating redirects.
- 🔑 **In-Game YouTube OAuth Login (`/musiclogin`):**
  - Captures Google verification codes directly from logs to show clickable login links and copyable codes in player chat and on-screen titles.
  - Refresh token automatically saved to `youtube.yml` without needing server restart.
- 🤖 **AI-Assisted Search & Suggestions (Optional):**
  - Use `/music search <vibe or description>` (e.g. `/music search relaxing lofi study beats`).
  - Automatic typo and lyric-snippet correction.
  - Compatible with OpenAI, Groq, OpenRouter, Google Gemini, Ollama, and Anthropic Claude.
- 📊 **Interactive Bossbar & Volume Control:**
  - Real-time animated bossbar showing track progress and status.
  - Integrated with Simple Voice Chat's volume settings: players can customize music volume in the client menu (`press V`).
- 📱 **Bedrock / Geyser Aware:** Detects Floodgate players and gives clear instructions for browser-based voice bridges.

---

## 📋 Requirements

- **Java 21** or newer
- **Paper** 1.20.6 / 1.21+ (or modern Paper forks)
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) **2.6.0+** on server & clients

---

## 📦 Building from Source

```bash
git clone https://github.com/muhfajrigg/GroupMusic.git
cd GroupMusic
mvn clean package
```

The compiled JAR will be in `target/GroupMusic-1.0.0.jar`. Place it into your server's `plugins/` directory.

---

## 📖 Usage & Quick Command Cheatsheet

GroupMusic can be controlled using the primary commands `/music` or `/lagu` (shortcut `/l`). Below is a quick command reference for everyday use:

| Command | Aliases | Description | Permission |
|---|---|---|---|
| `/lagu play <judul/url>` | `/l p`, `/music play` | Play or add a song to the voice chat group queue | `groupmusic.use` |
| `/lagu pause` / `/lagu resume` | `/l pause`, `/l resume` | Pause or resume track playback | `groupmusic.use` |
| `/lagu skip` | `/l s`, `/music skip` | Skip the currently playing track | `groupmusic.use` |
| `/lagu queue` | `/l q`, `/music queue` | View the current group track queue | `groupmusic.use` |
| `/lagu volume <0-100>` | `/l vol`, `/music volume` | Adjust the group audio volume | `groupmusic.use` |
| `/lagu loop [off/track/queue]` | `/l lp`, `/music loop` | Configure track loop mode | `groupmusic.use` |
| `/lagu search <deskripsi>` | `/l cari`, `/music search` | Smart AI-powered song search (optional) | `groupmusic.use` |
| `/lagu stop` | `/l stop`, `/music stop` | Stop playback and clear the queue | `groupmusic.use` |
| `/lagu help` | `/l h`, `/music help` | View interactive command guide | `groupmusic.use` |
| `/lagureload` | `/musicreload` | Reload configuration files without server restart | `groupmusic.admin` |

---

## 🎮 All Commands & Permissions

All standalone commands also support English and Indonesian aliases:

| Command | Aliases | Description | Permission |
|---|---|---|---|
| `/music <title or link>` | `/lagu`, `/play`, `/gm`, `/l` | Queue and play a track directly | `groupmusic.use` |
| `/musicsearch <description>` | `/lagucari`, `/search` | Search songs using AI mood/vibe | `groupmusic.search` |
| `/musicstop` | `/lagustop`, `/mstop` | Stop playback and clear the queue | `groupmusic.use` |
| `/musicpause` | `/lagupause`, `/pause` | Pause or resume current track | `groupmusic.use` |
| `/musicskip` | `/laguskip`, `/skip` | Skip currently playing track | `groupmusic.use` |
| `/musicqueue` | `/laguqueue`, `/queue` | View upcoming tracks in queue | `groupmusic.use` |
| `/musicnow` | `/lagunow`, `/np` | Display current track info | `groupmusic.use` |
| `/musicloop [track\|queue\|off]` | `/lagulagi`, `/loop` | Set or toggle loop mode | `groupmusic.use` |
| `/musicshuffle` | `/lagushuffle`, `/shuffle` | Shuffle tracks in queue | `groupmusic.use` |
| `/musicvolume [0-100]` | `/laguvolume`, `/volume` | View or adjust group volume | `groupmusic.use` |
| `/musicbar` | `/lagubar`, `/mbar` | Toggle bossbar visibility | `groupmusic.use` |
| `/musiclogin` | `/lagulogin` | Authorize YouTube Google account | `groupmusic.admin` |
| `/musicreload` | `/lagureload` | Reload configs and language files | `groupmusic.admin` |

### Permissions
- `groupmusic.use`: Allows using basic music commands (default: all players).
- `groupmusic.search`: Allows using AI-assisted search (default: all players).
- `groupmusic.ai.bypass-cooldown`: Bypasses cooldown on AI search (default: OP).
- `groupmusic.admin`: Administrative access for login and reload (default: OP).

---

## ⚙️ Configuration

Configuration files are located in `plugins/GroupMusic/`:

| File | Description | Reloadable via `/musicreload` |
|---|---|---|
| `config.yml` | Queue limits, track duration, idle timeout, bossbar, language (`locale`) | Yes |
| `messages.yml` | Player-facing messages in English (MiniMessage format) | Yes |
| `messages_id.yml` | Player-facing messages in Indonesian | Yes |
| `sources.yml` | Allowed domains, Spotify API credentials, and search providers | Yes (Spotify keys: restart) |
| `youtube.yml` | YouTube client order, remote cipher URL, and OAuth tokens | Requires restart |
| `ai.yml` | AI API provider, model, keys, prompts, and rate limits | Yes |

To switch language to Indonesian, set `locale: id` in `config.yml` and run `/musicreload`.

---

### 🔑 YouTube Authentication (Recommended One-Time Setup)

Due to YouTube's strict anti-bot measures, playing official music videos or age-restricted tracks may require authentication. GroupMusic features a built-in OAuth login command directly in-game:

1. Type `/musiclogin` (or `/lagulogin`) in console or in-game (requires `groupmusic.admin`).
2. Open the displayed Google authentication link and enter the provided pairing code.
3. Log in using a **spare / burner Google account** (recommended best practice to protect your personal account).
4. Once verified, session tokens are securely saved in `youtube.yml`. You only need to do this once!

---

## 📄 License

This project is licensed under the [MIT License](LICENSE) - see the LICENSE file for details.  
Copyright (c) 2026 **muhfajrigg** ([@muhfajrigg](https://github.com/muhfajrigg)).

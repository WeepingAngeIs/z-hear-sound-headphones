# Mobs Hear U

A standalone proximity voice prototype for Minecraft Java 1.21.1, NeoForge 21.1.255 and Java 21. Nearby players can hear your voice. If you enable local mob calls, nearby hostiles can also react to short spoken names.

## Controls

- **Hold V** while playing to speak through the default microphone. Nearby players running the mod hear you through their default output. Volume falls linearly with distance. The default range is 32 blocks, and a dimension boundary stops the sound.
- **Press N** to enable English mob calls for this connection. Wait for **Mob calls ready**, then hold V and say a short phrase such as **hey zombie**. Release V or leave a short pause. Press N again to turn recognition off. Both keys can be rebound under **Options → Controls → Key Binds → Mobs Hear U**.
- Speech recognition uses the installed English Microsoft desktop recognizer on Windows. Its availability is checked when enabled. Voice transmission and playback use Java Sound and work independently of that recognizer. The client reports microphone, output and recognizer availability in the action bar.

| Say while holding V | In-game response |
| --- | --- |
| zombie, zombies, zombified piglin | Eligible zombies pause, face you and can switch their target to you. The family includes husks, drowned, zombie villagers and zombified piglins. |
| mobs | Eligible nearby hostiles look toward you. Zombies also get their normal voice reaction. |
| enderman, endermen | Endermen glance toward you. Vanilla eye contact and existing targets still govern aggression. |
| hey | No mob cue. |

Passive mobs ignore calls. Creative players can talk to other players. Mob calls require an eligible survival or adventure speaker. A zombie can pause for ten game ticks at most once per forty ticks.

## Server and privacy

Use the same mod on the server and participating clients. Set `voiceRadius` in the world's `serverconfig/mobsear-server.toml` to a distance from 1 to 96 blocks. The server takes the speaker, dimension and position from the game connection. It rate-limits incoming voice and accepts a mob cue only shortly after accepted voice frames. A cue is a claim from the client. The server cannot authenticate the words that produced it.

The prototype sends mono 16 kHz PCM over the existing Minecraft connection. A continuously talking player sends about 32 KB/s of audio before packet overhead. The server relays it only to eligible nearby modded listeners. Recognition runs in memory on the speaker's PC and sends a single cue ID. The mod includes no audio recording, speech service, voice logging or transcript in game chat.

## Build and checks

GitHub Actions builds the production jar with `./gradlew build`, runs deterministic audio and rate-limit fixtures, and exercises routing and mob AI in a disposable dedicated NeoForge GameTest world. The Windows job synthesizes speech in memory and passes it to the same desktop recognition helper used by the game. Test fixtures use generated input and do not need a microphone. [Hosted checks](https://github.com/WeepingAngeIs/z-hear-sound-headphones/actions/workflows/prototype.yml)

Simple Voice Chat's device setup, push-to-talk behavior and configurable hearing distance were functional references. This project supplies its own transport, client controls and gameplay logic. [Client setup](https://modrepo.de/minecraft/voicechat/wiki/client_setup) · [Server settings](https://modrepo.de/minecraft/voicechat/wiki/server_config) · [NeoForge payload registration](https://docs.neoforged.net/docs/1.21.1/networking/payload/)

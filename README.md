# Mobs Hear U

A standalone proximity voice mod for Minecraft Java 1.21.1, Java 21 and NeoForge 21.1.249 or later in the 21.1 series. Nearby players can hear your voice. Enabling local mob calls also lets you call nearby Minecraft mobs, modded creatures and wild Cobblemon by name.

## Controls

- **Hold V** while playing to speak through your selected microphone. Nearby players running the mod hear you through their selected output. Volume falls linearly with distance. The default range is 32 blocks, and a dimension boundary stops the sound.
- **Press N** to enable English mob calls for this connection. Wait for **Mob calls ready**, then hold V and say a short phrase such as **hey zombie**. Release V or leave a short pause. A private action-bar message identifies the recognized call. Survival and Adventure can call mobs. Creative and Spectator cannot. Press N again to turn recognition off.
- **Press G** to open the menu. Scroll the left controls to select devices, adjust voice volume, enable the local mic meter, switch to voice activation, set its sensitivity, or enable the noise filter. The meter consumes local input only while enabled in this screen. **M** toggles mute. Deafen stops input and playback together. Rebind keys under **Options → Controls → Key Binds → Mobs Hear U**.
- Scroll the right pane independently to see other online players and set each person's volume from 0–200%. The roster indicates audible speech. The nearby name-tag indicator follows the speaker’s activity.
- Choose **UwU**, **Dark / Emo**, **Vanilla**, or **Amethyst** on the left. Each family opens on its first animated scene. **Backdrop** cycles through four scenes and the plain Original, and the menu remembers your choice for each family. The eight-frame pixel scenes loop every 3.2 seconds. **Motion → Reduced** or **Off** holds the first scene frame.
- **Voice Changer** opens its own themed screen. Its slider covers one octave down through natural to one octave up in one-cent increments. **Deep** selects −5 semitones, **Squeaky** selects +7 and **Custom** recalls your last manual setting. **Off** passes through your natural voice and remembers the selection. The preset, last manual position and bypass setting persist for each local Minecraft account, including after a restart. **Reset to Defaults** puts just the changer back at natural with the effect off. The effect processes outgoing player audio locally. Mob recognition continues to hear the original input.
- Leave **Mic style** on **Theme** to match the menu, or choose a mic independently. Nearby players can see that style beside your visible name tag. A slash marks mute, headphones mark deafen, and a speaking mic brightens. Full motion adds a small shake and short-lived pixel particles. You can choose a local accent and hide your HUD or other players’ mic icons separately.
- Appearance, device selections, playback volume, filters and individual gains are saved locally in `config/mobsear-client.properties`. Voice activation, the mic test, mob calls and quiet-horror participation require opt-in.
- Speech recognition uses the installed English Microsoft desktop recognizer on Windows. Its availability is checked when enabled. Voice transmission and playback use Java Sound and work independently of that recognizer. The client reports microphone, output and recognizer availability in the action bar.

| Say while holding V | In-game response |
| --- | --- |
| zombie, zombies, zombified piglin | Eligible zombies pause, face you and can switch their target to you. The family includes husks, drowned, zombie villagers and zombified piglins. |
| mob, mobs | Eligible nearby hostiles look toward you. Zombies, calm Endermen and calm spiders use their normal voice reactions. |
| enderman, endermen | A calm Enderman briefly pauses and turns its head toward the sound. Eye contact, anger and an existing fight continue under vanilla AI. |
| skeleton, spider, guardian, slime | The named family looks toward the speaker, including variants such as bogged, cave spiders, elder guardians or magma cubes. |
| creeper, witch, blaze, breeze and other supported hostile names | The matching eligible mob looks toward the speaker. |
| hey | No mob cue. |

Passive mobs can hear their individual type names. The broad **mob** and **mobs** calls apply to hostiles. Creative players can talk to other players. Mob calls require an eligible Survival or Adventure speaker. A zombie can pause for ten game ticks at most once per forty ticks.

Calm Endermen, spiders and cave spiders can pause for up to 18 game ticks (about 0.9 seconds) and then resume walking. Each can react once every 40 ticks. With the default server setting, their hearing range extends through 32 blocks in the same dimension.

After first enabling recognition on Windows, edit `config/mobsear-aliases.properties` to add local aliases. For example, `skeleton=bone walker,bones`. Use up to four comma-separated English aliases per supported name, each one to three words. Toggle mob calls off and on after editing. Built-in names retain their meaning and a standalone “hey” stays silent. Supported names beyond the table include pillager, ghast, phantom, silverfish, endermite, shulker, vindicator, evoker, vex, ravager, hoglin, zoglin, piglin brute, warden and wither.

## Calling vanilla and modded mobs

The client reads English mob names from Minecraft’s entity registry and includes the type’s registry spelling with underscores spoken as spaces. Say its name or **call** followed by the full name. A matching, calm mob within the voice radius can turn toward the caller. Its navigation, combat target and attack phases remain under its own AI. Some bosses have scripted poses or models that cannot display a head turn in every state. Items, projectiles, players and armor stands are outside the mob vocabulary.

In **UwU Dimension**, the Guardian’s English name is **Owl Guardian**. The girl and male variants both use `uwudimension:blossom_warden`. The mod also accepts the name **UwU Guardian**. **Velvet Vesper** is a separate boss. **Guardian** alone keeps the Minecraft underwater guardian call. Other pack examples include **Twilight Lich**, **Ender Guardian**, **Ancient Remnant** and **Valkyrie Queen**. The private recognition message shows the mob type selected by the recognizer.

## Calling wild Cobblemon

With Cobblemon on the client and server, enable **Local Mob Calls** and say a nearby wild Pokémon’s species name, such as **Pikachu** or **Eevee**. Use push-to-talk or voice activation, then leave a short pause. The private action bar confirms the species the local recognizer heard. The same configured voice radius and Survival or Adventure requirement apply.

A calm wild Pokémon looks toward the sound. A land Pokémon with room to walk has a **20% chance** to approach up to four blocks using ordinary pathfinding. Each Pokémon has a five-second cooldown. Server owners can set `wildPokemonApproachChance` between `0.0` and `1.0`. Setting it to `0.0` keeps the glance alone. Battles, capture interactions and owned Pokémon keep their normal behavior.

The vocabulary comes from Cobblemon’s synced species registry, including names supplied by datapacks. English desktop recognition uses canonical species names and English display names when the game uses English. Localized labels still appear in the private feedback. Duplicate spoken names are skipped, and existing mob names retain their meanings. For the two Nidoran species, say **Nidoran female** or **Nidoran male**. Only the matched species identifier goes to the server.

For similar-sounding species, a call can go unanswered when the recognizer cannot confirm the name. Try saying the name followed by its ending spelled aloud, such as **Spectreon ending O N** or **Spectrier ending E R**.

## Server and privacy

Quiet horror is a separate server option. Set `hordeEnabled = true` in the server configuration to offer it. Each player then opts in from the voice menu for that connection. Death, a dimension change, disconnecting, Peaceful difficulty, or switching to creative or spectator ends participation.

About 1.2 seconds of sustained audible input can start an encounter, regardless of words or recognition settings. The server measures the level of accepted microphone frames. Nearby idle zombies, husks, drowned and zombie villagers can pursue the speaker using their normal attack AI. Existing fights, Endermen and zombified piglins keep their usual behavior.

Encounters last up to 20 seconds. The default `hordeCooldownSeconds` is 45 and `hordeGroupCap` is 4. Difficulty also caps each group at two zombies on Easy, three on Normal and four on Hard. At most 24 zombies participate across the server. When an encounter has open spots and mob spawning is enabled, temporary zombies can appear in a loaded, dark, unobstructed place at least five blocks from the speaker, within the voice radius. Each temporary zombie can target only that participant and drops no loot or experience. Leaving range, opting out or ending the encounter removes temporary zombies and releases the living world mobs recruited by the encounter.

Use the same mod on the server and participating clients. Set `voiceRadius` in the server’s `config/mobsear-server.toml` or its world-specific `serverconfig/mobsear-server.toml` override to a distance from 1 to 96 blocks. The server takes the speaker, dimension and position from the game connection. It rate-limits incoming voice and accepts a mob cue only shortly after accepted voice frames. A cue is a claim from the client. The server cannot authenticate the words that produced it.

The mod sends mono 16 kHz PCM over the existing Minecraft connection. A continuously talking player sends about 32 KB/s of audio before packet overhead. The server relays it only to eligible nearby modded listeners. Recognition runs in memory on the speaker's PC and sends a single cue ID. The outgoing noise filter and voice changer leave the recognition input unprocessed. The mod includes no audio recording, external speech service, voice logging or transcript in game chat.

## Voice feature coverage

| Feature | Mobs Hear U |
| --- | --- |
| Proximity and range | Configurable spherical radius, same-dimension routing and distance-dependent volume. Output is mono. |
| Speaking and listening controls | Rebindable push-to-talk, opt-in voice activation, activation sensitivity, device selection, mic mute, deafen, local mic meter, master and per-player gain. |
| Noise processing | Optional local 80 Hz high-pass filter and a soft gate. |
| Transport | PCM over the existing Minecraft connection. Connection security follows the Minecraft session. |
| Features specific to Simple Voice Chat | Directional 3D audio, whisper, password-protected groups, Opus/UDP transport, RNNoise, automatic microphone gain, speaker test playback, a plugin API and multi-loader/proxy support are outside this build. Recording is excluded by design. |

The comparison uses the [official feature list](https://github.com/henkelmax/simple-voice-chat#features) and [client setup guide](https://modrepo.de/minecraft/voicechat/wiki/client_setup).

## Build and checks

GitHub Actions builds the production jar with `./gradlew build`, runs synthetic audio and rate-limit checks, and exercises mob AI in a disposable dedicated NeoForge GameTest world. Two modded game clients exchange generated audio near each other, far apart and across dimensions. The menu check uses the same clients and captures each theme in Minecraft. The Windows job synthesizes English speech in memory and passes it through the push-to-talk segmenter and desktop recognizer. [Hosted checks](https://github.com/WeepingAngeIs/z-hear-sound-headphones/actions/workflows/prototype.yml)

Simple Voice Chat groups device selection with voice settings and gives individual players their own gain controls. Those groupings and its configurable hearing distance informed this prototype. The themes, menu layout, transport and gameplay logic are original to Mobs Hear U. [Official settings layout](https://github.com/henkelmax/simple-voice-chat#settings) · [Player volume list](https://github.com/henkelmax/simple-voice-chat/tree/137d62f6c93a3d02b50a1b67964814e6c24a16d4/common-client/src/main/java/de/maxhenkel/voicechat/gui/volume) · [Server settings](https://modrepo.de/minecraft/voicechat/wiki/server_config) · [NeoForge payload registration](https://docs.neoforged.net/docs/1.21.1/networking/payload/)

A separate server-owner setting to disable hostile mob calls is planned for a later version.

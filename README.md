# Mobs Hear U

A standalone proximity voice mod for Minecraft Java 1.21.1, Java 21 and NeoForge 21.1.249 or later in the 21.1 series. Nearby players can hear your voice. Enabling local mob calls also lets you call nearby Minecraft mobs, modded creatures and wild Cobblemon by name.

## Controls

- **Speak while playing** to talk to nearby players. Fresh profiles start unmuted with **Voice activated** and **Local mob calls** on. Open **G → Input mode** to choose **Push-to-talk**, then hold **V** when you want to speak. The input threshold, selected microphone and operating system microphone permission apply. The default voice range is 32 blocks within the same dimension.
- **N** toggles local English mob calls and saves your choice. Once **Mob calls ready** appears, a supported name can be spoken on its own or as a whole word in conversation, as in “Hey, look, there's a Lopunny.” Longer speech is processed at brief pauses. In Survival or Adventure, a pink notice above your hearts or armor names the nearby mob that the server confirmed heard you.
- **Local Mob Call Debug Log** in the **G** menu is off by default. Turn it on to see recognizer readiness, errors, missed names, detected registry names and call outcomes in this Minecraft instance’s `logs/latest.log`. Search for `MOBSEAR_CALL_DEBUG`. The caller can distinguish a confirmed response, no eligible mob nearby, and a server that never replied. Entries are rate-limited and contain no audio, guesses or sentence text.
- **Speech Test Feedback** in the voice menu is off by default. Turn it on to see when the mod starts listening, which name the recognizer picked, or its best guess when a call is rejected. If it has no guess, the notice reads **No mob name matched**. The guesses appear only on your screen and expire. Only the game's confirmation shows **heard you**.
- **Press G** to open the menu. Scroll the left controls to select devices, adjust voice volume, enable the local mic meter, choose an input mode, set its sensitivity, or enable the noise filter. The meter consumes local input only while enabled in this screen. **M** toggles mute. Deafen stops input and playback together. Rebind keys under **Options → Controls → Key Binds → Mobs Hear U**.
- Scroll the right pane independently to see other online players and set each person's volume from 0–200%. The roster indicates audible speech. The nearby name-tag indicator follows the speaker’s activity.
- Choose **UwU**, **Dark / Emo**, **Vanilla**, or **Amethyst** on the left. Each family opens on its first animated scene. **Backdrop** cycles through four scenes and the plain Original, and the menu remembers your choice for each family. The eight-frame pixel scenes loop every 3.2 seconds. **Motion → Reduced** or **Off** holds the first scene frame.
- **Voice Changer** opens its own themed screen. Its slider covers one octave down through natural to one octave up in one-cent increments. **Deep** selects −5 semitones, **Squeaky** selects +7 and **Custom** recalls your last manual setting. **Off** passes through your natural voice and remembers the selection. The preset, last manual position and bypass setting persist for each local Minecraft account, including after a restart. **Reset to Defaults** puts just the changer back at natural with the effect off. The effect processes outgoing player audio locally. Mob recognition continues to hear the original input.
- Leave **Mic style** on **Theme** to match the menu, or choose a mic independently. Nearby players can see that style beside your visible name tag. A slash marks mute, headphones mark deafen, and a speaking mic brightens. Full motion adds a small shake and short-lived pixel particles. You can choose a local accent and hide your HUD or other players’ mic icons separately.
- Your input mode, Local Mob Calls, mute and deafen choices are saved in `config/mobsear-client.properties` together with your other local settings. Existing settings files from 0.3.4 keep push-to-talk and mob calls off until you change them. That version kept those two switches only for the current connection. The mic test and quiet-horror mode are separately enabled.
- Local Mob Calls uses the English desktop speech recognizer installed in Windows. The mod sends the opted-in player's short voice segments over a private memory pipe to a Java worker, which calls the Windows Speech API directly. The same Tiny English speech weights used in 0.3.13 run through Windows Machine Learning for conversational names. The JAR contains model data and licenses. Registry names, whole-word pronunciation alternatives and the nearby roster guide recognition. Voice transmission and playback use Java Sound.

| Say while speaking | In-game response |
| --- | --- |
| zombie, zombies, zombified piglin | Eligible zombies pause, face you and can switch their target to you. The family includes husks, drowned, zombie villagers and zombified piglins. |
| hey mobs, mob, mobs | Draws the attention and attack target of each eligible hostile within earshot. Calm Endermen and spiders use their usual listening response. |
| enderman, endermen | A calm Enderman briefly pauses and turns its head toward the sound. Eye contact, anger and an existing fight continue under vanilla AI. |
| hey skeletons, look over here | Each eligible skeleton, including strays, bogged and wither skeletons, turns on you. Other mob families keep their targets. |
| hey creeper, hey zombie, guardian, slime and other supported hostile names | Matching hostiles can switch from a friend to you and use their normal attacks. A creeper walks into fuse range on its own. |
| hey | No mob cue. |

Passive mobs can hear their individual type names. The broad **mob** and **mobs** calls apply to hostiles. Living players can call mobs in Survival, Adventure, and Creative. Calm mobs can turn toward a Creative player while normal Creative combat immunity applies. Wild Pokémon respond to their base species name across all forms. For example, saying **Geodude** calls nearby regular and Alolan Geodude. A zombie can pause for ten game ticks at most once per forty ticks.

Calm Endermen, spiders and cave spiders can pause for up to 18 game ticks (about 0.9 seconds) and then resume walking. A spider already attacking someone can be drawn to the caller. The default hearing range is 32 blocks in the same dimension. A successful hostile distraction has a two-second cooldown per mob. An active creeper fuse, the Warden's anger system and scripted boss fights keep their native behavior.

After first enabling recognition on Windows, edit `config/mobsear-aliases.properties` to add local aliases. For example, `skeleton=bone walker,bones`. Use up to four comma-separated English aliases per supported name, each one to three words. Toggle mob calls off and on after editing. Built-in names retain their meaning and a standalone “hey” stays silent. Supported names beyond the table include pillager, ghast, phantom, silverfish, endermite, shulker, vindicator, evoker, vex, ravager, hoglin, zoglin, piglin brute, warden and wither.

## Calling vanilla and modded mobs

The client reads English mob names from Minecraft’s entity registry and includes the type’s registry spelling with underscores spoken as spaces. Say its name or **call** followed by the full name. An ordinary hostile can take the caller as its attack target through Minecraft's goal or brain targeting. The mob controls its own path, attacks and later target changes. Calm passive mobs and eligible bosses can turn briefly toward the sound. Some bosses have scripted poses or models that cannot display a head turn in every state. Items, projectiles, players and armor stands are outside the mob vocabulary.

In **UwU Dimension**, the Guardian’s English name is **Owl Guardian**. The girl and male variants both use `uwudimension:blossom_warden`. The mod also accepts the name **UwU Guardian**. **Velvet Vesper** is a separate boss. **Guardian** alone keeps the Minecraft underwater guardian call. Other pack examples include **Twilight Lich**, **Ender Guardian**, **Ancient Remnant** and **Valkyrie Queen**. For the UwU boss, **call oo woo Guardian** worked in the generated Windows speech test.

Say **Dungeon Mimic** for Unyielding’s tagged Mimic. That encounter uses an ordinary mob with the `cu_mimic` marker, which the server checks. A zombie renamed with a name tag does not qualify. For randomly titled Apotheosis invaders or Infernal Mobs, use the underlying mob’s type name.

## Calling wild Cobblemon

With Cobblemon on the client and server, enable **Local Mob Calls** and say a nearby wild Pokémon’s species name, such as **Pikachu** or **Eevee**. Use push-to-talk or voice activation, then leave a short pause. When an eligible wild Pokémon responds, only the caller sees a notice such as **Lopunny heard you**. The pronunciations **LOP-uh-nee** and **LOW-pun-ee** both worked with two generated Windows voices. The same configured voice radius and Survival or Adventure requirement apply.

A calm wild Pokémon pauses its idle walk, faces the player who called its species name and uses its own pathfinding to come over. Its visit lasts six seconds (120 game ticks) from first arrival. It then resumes normal activity. A blocked Pokémon gives up if it cannot make progress. A fight, capture or other incompatible activity takes priority. Each Pokémon has a five-second call cooldown. Owned Pokémon keep their normal behavior.

While the Pokémon is beside you, right-click with an empty hand to pat its head. It shows hearts and makes its own cry. To feed it, right-click with a healing berry or drop one at its feet. A healthy visitor eats it as a treat. An injured visitor also recovers HP using the berry’s normal Cobblemon healing amount. Each feeding uses one berry and follows Cobblemon’s fullness rules. Only the player who called the Pokémon can feed or pat it during the six seconds after arrival. An uneaten drop stays on the ground.

The vocabulary comes from Cobblemon’s synced species registry, including names supplied by datapacks. English desktop recognition uses canonical species names and English display names when the game uses English. Localized labels still appear in the private feedback. Duplicate spoken names are skipped, and existing mob names retain their meanings. For the two Nidoran species, say **Nidoran female** or **Nidoran male**. Each species call identifies the registered species.

Exact names take priority. If the English decoder hears an imperfect name, it compares the sound and spelling against eligible wild species inside the server’s call radius. For equally plausible names, the closer Pokémon’s species wins. A matching initial alone is insufficient. You can still spell the ending explicitly, such as **Spectreon ending O N** or **Spectrier ending E R**.

## Server and privacy

Quiet horror is a separate server option. Set `hordeEnabled = true` in the server configuration to offer it. Each player then opts in from the voice menu for that connection. Death, a dimension change, disconnecting, Peaceful difficulty, or switching to creative or spectator ends participation.

About 1.2 seconds of sustained audible input can start an encounter, regardless of words or recognition settings. The server measures the level of accepted microphone frames. Nearby idle zombies, husks, drowned and zombie villagers can pursue the speaker using their normal attack AI. Existing fights, Endermen and zombified piglins keep their usual behavior.

Encounters last up to 20 seconds. The default `hordeCooldownSeconds` is 45 and `hordeGroupCap` is 4. Difficulty also caps each group at two zombies on Easy, three on Normal and four on Hard. At most 24 zombies participate across the server. When an encounter has open spots and mob spawning is enabled, temporary zombies can appear in a loaded, dark, unobstructed place at least five blocks from the speaker, within the voice radius. Each temporary zombie can target only that participant and drops no loot or experience. Leaving range, opting out or ending the encounter removes temporary zombies and releases the living world mobs recruited by the encounter.

Use the same mod on the server and participating clients. Set `voiceRadius` in the server’s `config/mobsear-server.toml` or its world-specific `serverconfig/mobsear-server.toml` override to a distance from 1 to 96 blocks. The server takes the speaker, dimension and position from the game connection. It rate-limits incoming voice and accepts a mob cue only shortly after accepted voice frames. A cue is a claim from the client. The server cannot authenticate the words that produced it.

The mod sends mono 16 kHz PCM over the existing Minecraft connection. A continuously talking player sends about 32 KB/s of audio before packet overhead. The server relays it only to eligible nearby modded listeners. Recognition runs in memory on the speaker's PC. A mob call carries its cue or registry ID and a request number so an old reply cannot confirm a later call. The outgoing noise filter and voice changer leave the recognition input unprocessed. The mod includes no audio recording, external speech service, voice logging or transcript in game chat.

## Voice feature coverage

| Feature | Mobs Hear U |
| --- | --- |
| Proximity and range | Configurable spherical radius, same-dimension routing and distance-dependent volume. Output is mono. |
| Speaking and listening controls | Voice activation, rebindable push-to-talk, activation sensitivity, device selection, mic mute, deafen, local mic meter, master and per-player gain. |
| Noise processing | Optional local 80 Hz high-pass filter and a soft gate. |
| Transport | PCM over the existing Minecraft connection. Connection security follows the Minecraft session. |
| Features specific to Simple Voice Chat | Directional 3D audio, whisper, password-protected groups, Opus/UDP transport, RNNoise, automatic microphone gain, speaker test playback, a plugin API and multi-loader/proxy support are outside this build. Recording is excluded by design. |

The comparison uses the [official feature list](https://github.com/henkelmax/simple-voice-chat#features) and [client setup guide](https://modrepo.de/minecraft/voicechat/wiki/client_setup).

## Build and checks

Source builds use Java 21 and Python 3.10–3.12. Gradle creates a build-only Python environment to convert the pinned English model into two standard ONNX data files. Players use the model offline through Windows ML.

GitHub Actions builds the production jar with `./gradlew build`, runs synthetic audio and rate-limit checks, and exercises mob AI in a disposable dedicated NeoForge GameTest world. Two modded game clients exchange generated audio near each other, far apart and across dimensions. The menu check uses the same clients and captures each theme in Minecraft. The Windows job synthesizes English speech in memory and tests it through push-to-talk and voice activation with the desktop recognizer. The focused comparison uses the same generated voices for version 0.3.0 and the current version, with silence, background noise and ordinary conversation as controls. [Hosted checks](https://github.com/WeepingAngeIs/z-hear-sound-headphones/actions/workflows/prototype.yml)

Simple Voice Chat groups device selection with voice settings and gives individual players their own gain controls. Those groupings and its configurable hearing distance informed this prototype. The themes, menu layout, transport and gameplay logic are original to Mobs Hear U. [Official settings layout](https://github.com/henkelmax/simple-voice-chat#settings) · [Player volume list](https://github.com/henkelmax/simple-voice-chat/tree/137d62f6c93a3d02b50a1b67964814e6c24a16d4/common-client/src/main/java/de/maxhenkel/voicechat/gui/volume) · [Server settings](https://modrepo.de/minecraft/voicechat/wiki/server_config) · [NeoForge payload registration](https://docs.neoforged.net/docs/1.21.1/networking/payload/)

A separate server-owner setting to disable hostile mob calls is planned for a later version.

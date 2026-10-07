# Free LOD (Fabric 26.3)
Client-only mod. Draws simplified far terrain (flat colored blocks) beyond your real render distance.
LOD distance has NO minimum and is independent of render distance:
set Minecraft render distance to 2 and LOD distance to 10 in Mod Menu -> Free LOD -> Configure.

Build: ./gradlew build (JDK 25) -> build/libs/freelod-1.0.0.jar (not -sources).
Config file: config/freelod.json  (also editable in-game through Mod Menu)

LODs come from (1) chunks you have already seen and (2) in singleplayer, chunks the integrated
server generates for you when "Generate in singleplayer" is ON (slow trickle, configurable).
LODs are kept in memory only and cleared when you leave the world.

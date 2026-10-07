package com.freelod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Every setting lives here. The Mod Menu screen is generated from these definitions,
 * so adding a setting = adding one line in the static block.
 * Saved to config/freelod.json.
 */
public final class LodConfig {
	public record Def(String cat, String key, String label, String desc, boolean bool,
					  double def, double min, double max, double step) {}

	private static final List<Def> ALL = new ArrayList<>();
	private static final Map<String, Double> VALUES = new HashMap<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<Map<String, Double>>() {}.getType();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("freelod.json");

	private static void b(String cat, String key, String label, boolean def, String desc) {
		ALL.add(new Def(cat, key, label, desc, true, def ? 1 : 0, 0, 1, 1));
	}

	private static void n(String cat, String key, String label, double def, double min, double max, double step, String desc) {
		ALL.add(new Def(cat, key, label, desc, false, def, min, max, step));
	}

	static {
		// ------------------------------------------------ Distance
		b("Distance", "enabled", "LOD enabled", true, "Master switch for the far-terrain rendering.");
		n("Distance", "lod_distance", "LOD distance (chunks)", 10, 0, 128, 1,
			"How far LODs reach, in chunks. Independent of your render distance - no minimum. Example: render distance 2 + LOD 10.");
		n("Distance", "start_offset", "Start offset (chunks)", 0, -8, 16, 1,
			"Where LODs begin relative to the edge of your real render distance. 0 = right at the edge, negative = overlap (hides gaps), positive = leave a gap.");
		b("Distance", "circular", "Circular range", true, "Round LOD area instead of a square.");

		// ------------------------------------------------ Detail
		n("Detail", "sample_log2", "Detail (0 sharp - 4 rough)", 2, 0, 4, 1,
			"Block size of each LOD cell as a power of two: 0 = 1 block, 1 = 2, 2 = 4, 3 = 8, 4 = 16. Applies to chunks captured from now on.");
		n("Detail", "column_depth", "Column depth (blocks)", 8, 1, 128, 1,
			"How tall each LOD column is below the surface. Higher hides the underside when flying, lower is cheaper.");
		b("Detail", "merge_cells", "Merge identical cells", true, "Join neighbouring cells with the same height and colour into one big box (big FPS saver).");
		n("Detail", "max_boxes", "Max boxes per frame", 3000, 100, 50000, 100,
			"Hard cap on drawn boxes. Nearest ones are kept first. Lower = more FPS, more missing far terrain.");

		// ------------------------------------------------ Look
		n("Look", "brightness", "Brightness (%)", 100, 20, 200, 5, "Colour brightness of LOD terrain.");
		b("Look", "side_shading", "Side shading", true, "Darken the sides of columns so hills read as 3D.");
		b("Look", "show_water", "Show water colour", true, "Colour water surfaces as water. Off = show the sea floor colour.");

		// ------------------------------------------------ Performance
		n("Performance", "update_ticks", "List refresh (ticks)", 10, 1, 100, 1,
			"How often the list of visible LOD chunks is rebuilt. Higher = less CPU.");
		n("Performance", "max_stored_chunks", "Max stored chunks", 20000, 100, 200000, 100,
			"Memory cap for stored LOD chunks. Oldest are dropped past this.");

		// ------------------------------------------------ Generation (singleplayer)
		b("Generation", "generate", "Generate in singleplayer", false,
			"Singleplayer only: let the built-in server generate/read far chunks for LODs so you do not have to explore first. Can cause lag.");
		n("Generation", "gen_per_tick", "Chunks per tick", 1, 1, 8, 1, "How many far chunks the server may load each tick while generating.");
		n("Generation", "gen_every_ticks", "Generate every N ticks", 4, 1, 100, 1, "Pause between generation steps. Higher = less lag, slower fill.");
		b("Generation", "capture_visited", "Capture visited chunks", true, "Save LODs of chunks you load normally (works on servers too).");

		load();
	}

	private LodConfig() {}

	public static List<Def> defs() { return ALL; }

	public static double num(String key) {
		Double v = VALUES.get(key);
		if (v != null) return v;
		for (Def d : ALL) if (d.key().equals(key)) return d.def();
		return 0;
	}

	public static int i(String key) { return (int) Math.round(num(key)); }
	public static boolean bool(String key) { return num(key) > 0.5; }

	public static double clamp(Def d, double v) {
		v = Math.max(d.min(), Math.min(d.max(), v));
		return d.bool() ? (v > 0.5 ? 1 : 0) : Math.round(v / d.step()) * d.step();
	}

	public static void set(String key, double v) {
		for (Def d : ALL) if (d.key().equals(key)) { VALUES.put(key, clamp(d, v)); return; }
	}

	public static void load() {
		for (Def d : ALL) VALUES.put(d.key(), d.def());
		if (!Files.exists(FILE)) { save(); return; }
		try (Reader r = Files.newBufferedReader(FILE)) {
			Map<String, Double> m = GSON.fromJson(r, MAP_TYPE);
			if (m != null) for (Def d : ALL) if (m.containsKey(d.key())) VALUES.put(d.key(), clamp(d, m.get(d.key())));
		} catch (Exception e) {
			System.err.println("[freelod] could not read config, using defaults: " + e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) { GSON.toJson(VALUES, MAP_TYPE, w); }
		} catch (Exception e) {
			System.err.println("[freelod] could not save config: " + e);
		}
	}

	public static void reset() {
		for (Def d : ALL) VALUES.put(d.key(), d.def());
		save();
	}
}

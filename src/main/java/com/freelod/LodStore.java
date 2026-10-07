package com.freelod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Holds the simplified terrain ("LODs"). Everything is stored as a few coloured rectangles per chunk,
 * so memory is tiny. Capture can run on the client thread (visited chunks) or on the integrated
 * server thread (generation), so all shared state is concurrent / immutable.
 */
public final class LodStore {
	/** One flat-topped column. x/z/w/d are block offsets inside the chunk, top is the world Y of the top face. */
	public record Rect(int x, int z, int w, int d, int top, int rgb) {}

	public static final class Col {
		public final int cx, cz;
		public final Rect[] rects;
		final long stamp;
		Col(int cx, int cz, Rect[] rects, long stamp) { this.cx = cx; this.cz = cz; this.rects = rects; this.stamp = stamp; }
	}

	private static final Map<String, Map<Long, Col>> DIMS = new ConcurrentHashMap<>();
	private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();
	private static final AtomicLong CLOCK = new AtomicLong();

	/** Rebuilt every few ticks; read by the renderer. */
	public static volatile Col[] visible = new Col[0];

	private static int tickCounter = 0;
	private static int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE, lastEnd = -1, lastStart = -1;
	private static boolean genIdle = false;

	private LodStore() {}

	private static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL); }

	private static Map<Long, Col> dim(String dimKey) {
		return DIMS.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>());
	}

	public static void clearAll() {
		DIMS.clear();
		PENDING.clear();
		visible = new Col[0];
		genIdle = false;
	}

	// ------------------------------------------------------------------ capture

	public static void capture(String dimKey, ChunkAccess chunk, BlockGetter level) {
		ChunkPos pos = chunk.getPos();
		int step = 1 << LodConfig.i("sample_log2");
		int n = 16 / step;
		int[] h = new int[n * n];
		int[] c = new int[n * n];
		boolean water = LodConfig.bool("show_water");
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

		for (int gz = 0; gz < n; gz++) {
			for (int gx = 0; gx < n; gx++) {
				int lx = gx * step + step / 2;
				int lz = gz * step + step / 2;
				int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) - 1;
				BlockState st = chunk.getBlockState(mp.set(pos.getMinBlockX() + lx, top, pos.getMinBlockZ() + lz));
				if (!water) {
					int guard = 0;
					while (!st.getFluidState().isEmpty() && guard++ < 64) {
						top--;
						st = chunk.getBlockState(mp.set(pos.getMinBlockX() + lx, top, pos.getMinBlockZ() + lz));
					}
				}
				int idx = gz * n + gx;
				if (st.isAir()) {
					c[idx] = -1;
				} else {
					h[idx] = top + 1;
					c[idx] = st.getMapColor(level, mp).col;
				}
			}
		}

		List<Rect> out = new ArrayList<>();
		boolean merge = LodConfig.bool("merge_cells");
		boolean[] used = new boolean[n * n];
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int idx = z * n + x;
				if (used[idx] || c[idx] < 0) continue;
				int w = 1, d = 1;
				if (merge) {
					while (x + w < n && !used[z * n + x + w] && h[z * n + x + w] == h[idx] && c[z * n + x + w] == c[idx]) w++;
					boolean ok = true;
					while (ok && z + d < n) {
						for (int k = 0; k < w; k++) {
							int j = (z + d) * n + x + k;
							if (used[j] || h[j] != h[idx] || c[j] != c[idx]) { ok = false; break; }
						}
						if (ok) d++;
					}
				}
				for (int dz = 0; dz < d; dz++) for (int dx = 0; dx < w; dx++) used[(z + dz) * n + x + dx] = true;
				out.add(new Rect(x * step, z * step, w * step, d * step, h[idx], c[idx]));
			}
		}

		Map<Long, Col> map = dim(dimKey);
		map.put(key(pos.x, pos.z), new Col(pos.x, pos.z, out.toArray(new Rect[0]), CLOCK.incrementAndGet()));
		trim(map);
	}

	private static void trim(Map<Long, Col> map) {
		int max = LodConfig.i("max_stored_chunks");
		if (map.size() <= max + 256) return;
		List<Map.Entry<Long, Col>> all = new ArrayList<>(map.entrySet());
		all.sort(Comparator.comparingLong(e -> e.getValue().stamp));
		for (int i = 0; i < all.size() - max; i++) map.remove(all.get(i).getKey());
	}

	// ------------------------------------------------------------------ per-tick work (client thread)

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || !LodConfig.bool("enabled")) {
			visible = new Col[0];
			return;
		}
		tickCounter++;
		if (tickCounter % LodConfig.i("update_ticks") == 0) refresh(mc);
		if (LodConfig.bool("generate") && tickCounter % LodConfig.i("gen_every_ticks") == 0) generate(mc);
	}

	private static int startChunks(Minecraft mc) {
		return mc.options.getEffectiveRenderDistance() + LodConfig.i("start_offset");
	}

	private static void refresh(Minecraft mc) {
		String dimKey = mc.level.dimension().toString();
		Map<Long, Col> map = DIMS.get(dimKey);
		if (map == null || map.isEmpty()) { visible = new Col[0]; return; }

		int pcx = mc.player.getBlockX() >> 4;
		int pcz = mc.player.getBlockZ() >> 4;
		int start = startChunks(mc);
		int end = LodConfig.i("lod_distance");
		boolean circ = LodConfig.bool("circular");

		List<Col> list = new ArrayList<>();
		for (Col col : map.values()) {
			int dx = col.cx - pcx, dz = col.cz - pcz;
			if (Math.max(Math.abs(dx), Math.abs(dz)) <= start) continue; // real chunks already draw this
			boolean inside = circ ? (long) dx * dx + (long) dz * dz <= (long) end * end
				: Math.max(Math.abs(dx), Math.abs(dz)) <= end;
			if (inside) list.add(col);
		}
		list.sort(Comparator.comparingLong(c -> (long) (c.cx - pcx) * (c.cx - pcx) + (long) (c.cz - pcz) * (c.cz - pcz)));
		visible = list.toArray(new Col[0]);
	}

	// ------------------------------------------------------------------ singleplayer generation

	private static void generate(Minecraft mc) {
		IntegratedServer srv = mc.getSingleplayerServer();
		if (srv == null) return;

		int pcx = mc.player.getBlockX() >> 4;
		int pcz = mc.player.getBlockZ() >> 4;
		int start = startChunks(mc);
		int end = LodConfig.i("lod_distance");
		if (pcx != lastCx || pcz != lastCz || end != lastEnd || start != lastStart) {
			lastCx = pcx; lastCz = pcz; lastEnd = end; lastStart = start;
			genIdle = false;
		}
		if (genIdle || end <= start) return;

		String dimKey = mc.level.dimension().toString();
		Map<Long, Col> map = dim(dimKey);
		int want = LodConfig.i("gen_per_tick");
		List<int[]> picks = new ArrayList<>();

		search:
		for (int r = Math.max(0, start) + 1; r <= end; r++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring only
					int cx = pcx + dx, cz = pcz + dz;
					long k = key(cx, cz);
					if (map.containsKey(k) || PENDING.contains(k)) continue;
					picks.add(new int[] { cx, cz });
					if (picks.size() >= want) break search;
				}
			}
		}
		if (picks.isEmpty()) { genIdle = true; return; }

		ServerLevel sl = srv.getLevel(mc.level.dimension());
		if (sl == null) return;
		for (int[] p : picks) PENDING.add(key(p[0], p[1]));
		srv.execute(() -> {
			for (int[] p : picks) {
				try {
					ChunkAccess ca = sl.getChunk(p[0], p[1], ChunkStatus.FULL, true);
					if (ca != null) capture(dimKey, ca, sl);
				} catch (Throwable t) {
					System.err.println("[freelod] generation failed for chunk " + p[0] + "," + p[1] + ": " + t);
				} finally {
					PENDING.remove(key(p[0], p[1]));
				}
			}
		});
	}
}

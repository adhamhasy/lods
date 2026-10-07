package com.freelod.client;

import com.freelod.LodConfig;
import com.freelod.LodStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Client entrypoint: wires chunk capture, the per-tick refresh and the renderer. */
public class FreeLodClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LodConfig.load();

		// Save LODs of chunks you load normally (and refresh them when they unload, so edits are kept).
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (LodConfig.bool("enabled") && LodConfig.bool("capture_visited"))
				LodStore.capture(level.dimension().toString(), chunk, level);
		});
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (LodConfig.bool("enabled") && LodConfig.bool("capture_visited"))
				LodStore.capture(level.dimension().toString(), chunk, level);
		});

		ClientTickEvents.END_CLIENT_TICK.register(LodStore::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> LodStore.clearAll());

		LodRenderer.register();
	}
}

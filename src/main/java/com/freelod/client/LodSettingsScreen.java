package com.freelod.client;

import com.freelod.LodConfig;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings screen (opened from Mod Menu). Built only from buttons, generated from LodConfig definitions. */
public class LodSettingsScreen extends Screen {
	private final Screen parent;
	private int page = 0;

	public LodSettingsScreen(Screen parent) {
		super(Component.literal("Free LOD Settings"));
		this.parent = parent;
	}

	private static String show(double v) {
		return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
	}

	private void set(LodConfig.Def d, double v) {
		LodConfig.set(d.key(), v);
		LodConfig.save();
		this.rebuildWidgets();
	}

	private Button label(String text, int x, int y, int w) {
		Button b = Button.builder(Component.literal(text), btn -> {}).bounds(x, y, w, 20).build();
		b.active = false;
		return b;
	}

	@Override
	protected void init() {
		int left = this.width / 2 - 150;
		List<LodConfig.Def> defs = LodConfig.defs();

		int perPage = Math.max(3, (this.height - 34 - 34) / 22);
		int pages = Math.max(1, (defs.size() + perPage - 1) / perPage);
		if (page >= pages) page = pages - 1;
		if (page < 0) page = 0;
		int start = page * perPage;

		String cat = start < defs.size() ? defs.get(start).cat() : "";
		this.addRenderableWidget(label("Free LOD - " + cat + "  " + (page + 1) + "/" + pages, left, 6, 300));

		for (int i = 0; i < perPage && start + i < defs.size(); i++) {
			LodConfig.Def d = defs.get(start + i);
			int y = 32 + i * 22;
			double v = LodConfig.num(d.key());
			Tooltip tip = Tooltip.create(Component.literal(d.desc()));
			if (d.bool()) {
				this.addRenderableWidget(Button.builder(
						Component.literal(d.label() + ": " + (v > 0.5 ? "ON" : "OFF")),
						btn -> set(d, v > 0.5 ? 0 : 1))
					.bounds(left, y, 300, 20).tooltip(tip).build());
			} else {
				this.addRenderableWidget(Button.builder(Component.literal(d.label() + ": " + show(v)), btn -> {})
					.bounds(left, y, 200, 20).tooltip(tip).build());
				Button minus = Button.builder(Component.literal("-"), btn -> set(d, v - d.step()))
					.bounds(left + 204, y, 46, 20).tooltip(tip).build();
				Button plus = Button.builder(Component.literal("+"), btn -> set(d, v + d.step()))
					.bounds(left + 254, y, 46, 20).tooltip(tip).build();
				minus.active = v > d.min();
				plus.active = v < d.max();
				this.addRenderableWidget(minus);
				this.addRenderableWidget(plus);
			}
		}

		int by = this.height - 26;
		Button prev = Button.builder(Component.literal("< Prev"), btn -> { page--; this.rebuildWidgets(); })
			.bounds(left, by, 70, 20).build();
		prev.active = page > 0;
		Button next = Button.builder(Component.literal("Next >"), btn -> { page++; this.rebuildWidgets(); })
			.bounds(left + 230, by, 70, 20).build();
		next.active = page < pages - 1;
		this.addRenderableWidget(prev);
		this.addRenderableWidget(Button.builder(Component.literal("Reset all"), btn -> { LodConfig.reset(); this.rebuildWidgets(); })
			.bounds(left + 74, by, 70, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Done"), btn -> this.onClose())
			.bounds(left + 152, by, 74, 20).build());
		this.addRenderableWidget(next);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreenAndShow(parent); // confirmed: Minecraft.setScreenAndShow(Screen)
	}
}

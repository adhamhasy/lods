package com.freelod.client;

import com.freelod.LodConfig;
import com.freelod.LodStore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws every visible LOD rectangle as a flat-coloured box.
 *
 * !! VERSION-SENSITIVE FILE !!
 * Everything that touches Minecraft's rendering API is in this one file, because those names change
 * between versions. If the build fails, the errors will almost certainly be in the lines marked
 * "API:" below - fix only those (event name, context accessors, render type name, camera position).
 */
public final class LodRenderer {
	private LodRenderer() {}

	public static void register() {
		// API: event where mods hand geometry to the renderer (confirmed to exist in Fabric API for 26.3)
		LevelRenderEvents.COLLECT_SUBMITS.register(LodRenderer::render);
	}

	private static void render(LevelRenderContext ctx) {
		if (!LodConfig.bool("enabled")) return;
		LodStore.Col[] cols = LodStore.visible;
		if (cols.length == 0) return;

		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.mainCamera().position(); // confirmed: GameRenderer.mainCamera(), Camera.position()
		PoseStack pose = ctx.poseStack();                   // confirmed: LevelRenderContext.poseStack()

		pose.pushPose();
		pose.translate(-cam.x, -cam.y, -cam.z);
		// API (unverified): hands a draw callback to the renderer, which runs it later with a vertex buffer
		ctx.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugQuads(),
			(p, vc) -> draw(vc, p.pose(), cols));
		pose.popPose();
	}

	private static void draw(VertexConsumer vc, Matrix4f m, LodStore.Col[] cols) {
		float bright = (float) (LodConfig.num("brightness") / 100.0);
		boolean shade = LodConfig.bool("side_shading");
		int depth = LodConfig.i("column_depth");
		int budget = LodConfig.i("max_boxes");

		outer:
		for (LodStore.Col col : cols) {
			int bx = col.cx * 16, bz = col.cz * 16;
			for (LodStore.Rect r : col.rects) {
				if (budget-- <= 0) break outer;
				float x0 = bx + r.x(), x1 = x0 + r.w();
				float z0 = bz + r.z(), z1 = z0 + r.d();
				float y1 = r.top(), y0 = y1 - depth;

				int top = argb(r.rgb(), bright);
				int sx = argb(r.rgb(), bright * (shade ? 0.8f : 1f));
				int sz = argb(r.rgb(), bright * (shade ? 0.65f : 1f));

				quad(vc, m, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, top); // top
				quad(vc, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, sz);   // north
				quad(vc, m, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, sz);   // south
				quad(vc, m, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, sx);   // west
				quad(vc, m, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, sx);   // east
			}
		}
	}

	private static int argb(int rgb, float f) {
		int r = Math.min(255, Math.round(((rgb >> 16) & 255) * f));
		int g = Math.min(255, Math.round(((rgb >> 8) & 255) * f));
		int b = Math.min(255, Math.round((rgb & 255) * f));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static void quad(VertexConsumer vc, Matrix4f m,
							 float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz, int color) {
		vc.addVertex(m, ax, ay, az).setColor(color); // API: vertex builder calls
		vc.addVertex(m, bx, by, bz).setColor(color);
		vc.addVertex(m, cx, cy, cz).setColor(color);
		vc.addVertex(m, dx, dy, dz).setColor(color);
	}
}

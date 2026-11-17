package dev.gregedcolonies.test;

import dev.gregedcolonies.GregedColonies;
import dev.gregedcolonies.Facing;
import dev.gregedcolonies.MapScreen;
import dev.gregedcolonies.MapSession;
import dev.gregedcolonies.Projection;
import dev.gregedcolonies.Settings;
import dev.gregedcolonies.bake.TileKey;
import dev.gregedcolonies.data.ChunkSnapshot;
import java.lang.reflect.Method;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * A Create contraption far below the ground above it (opt-in: {@code create-buried}, with Create
 * installed): a mechanical bearing carrying one block in a chamber near bedrock, under a stone
 * slab at the top of the world, as a mine lift sits under a mountain. Its shadow has no ground to
 * fall on. The map zoomed out over it and the far-zoomed minimap once took the area between the
 * craft and the ground for the shadow's, upside down, and the game closed (#407). Create is
 * reached by reflection, so the section builds without it.
 */
final class CreateBuriedTest {
	private static final String CONTRAPTION = "com.simibubi.create.content.contraptions.AbstractContraptionEntity";
	private static final int SLAB = 319;
	private static final int BEARING = -58;

	private CreateBuriedTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		try {
			Class.forName(CONTRAPTION);
		} catch (ClassNotFoundException e) {
			throw new AssertionError("GREGEDCOLONIES_TESTS=create-buried needs Create installed");
		}
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.onGround(), 600);
		// The places below are worked out for a map facing north.
		Facing savedFacing = context.computeOnClient(mc -> Projection.facing());
		FacingTest.turnTo(context, Facing.NORTH);
		BlockPos start = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = start.getX() + 8;
		int z = aligned(context, start.getZ() + 8);
		// The ground the map knows above the craft: a slab at the top of the world.
		world.getServer().runCommand("fill " + (x - 24) + " " + SLAB + " " + (z - 24) + " " + (x + 24) + " " + SLAB + " " + (z + 24) + " smooth_stone");
		// The chamber, the bearing in it, and the one block it carries.
		world.getServer().runCommand("fill " + (x - 2) + " " + BEARING + " " + (z - 2) + " " + (x + 2) + " " + (BEARING + 4) + " " + (z + 2) + " air");
		world.getServer().runCommand("setblock " + x + " " + BEARING + " " + z + " create:mechanical_bearing[facing=up]");
		world.getServer().runCommand("setblock " + x + " " + (BEARING + 1) + " " + z + " red_wool");
		// The motor under it: a bearing nothing turns lets its blocks go again.
		world.getServer().runCommand("setblock " + x + " " + (BEARING - 1) + " " + z + " create:creative_motor[facing=up]");
		world.getServer().runCommand("tp @a " + (x - 6) + " " + (SLAB + 1) + " " + (z + 6));
		context.waitTicks(40);
		BlockPos bearing = new BlockPos(x, BEARING, z);
		world.getServer().runOnServer(server -> {
			Object be = server.overworld().getBlockEntity(bearing);
			if (be == null) {
				throw new AssertionError("No bearing block entity at " + bearing);
			}
			if (call(be, "getMovedContraption") == null) {
				call(be, "assemble");
			}
			if (call(be, "getMovedContraption") == null) {
				throw new AssertionError("The bearing didn't assemble");
			}
		});
		// The contraption on the client, on GregedColonies's list, and the slab as the ground over it.
		UUID[] id = {null};
		String[] missing = {null};
		for (int i = 0; i < 60; i++) {
			missing[0] = context.computeOnClient(mc -> {
				for (Entity entity : mc.level.entitiesForRendering()) {
					if (isContraption(entity)) {
						id[0] = entity.getUUID();
					}
				}
				if (id[0] == null) {
					return "The client has no contraption";
				}
				if (!MapSession.get().crafts().has(id[0])) {
					return "The map doesn't have the contraption";
				}
				ChunkSnapshot chunk = MapSession.get().map().chunk(x >> 4, z >> 4);
				if (chunk == null || chunk.surface(x, z) != SLAB) {
					return "The map's ground over the contraption is " + (chunk == null ? "unknown" : chunk.surface(x, z)) + ", not the slab at " + SLAB;
				}
				return null;
			});
			if (missing[0] == null) {
				break;
			}
			context.waitTicks(20);
		}
		if (missing[0] != null) {
			throw new AssertionError(missing[0]);
		}
		GregedColonies.LOGGER.info("Create buried test: contraption {} under {} {} {}, slab at {}", id[0], x, BEARING + 1, z, SLAB);
		int savedZoom = context.computeOnClient(mc -> Settings.get().zoomStep());
		try {
			// Close on the slab first, then out until the view holds both the slab and the craft's place.
			double u = context.computeOnClient(mc -> Projection.u(x, z));
			double v = context.computeOnClient(mc -> Projection.v(x, (SLAB + BEARING) / 2.0, z));
			context.setScreen(MapScreen::new);
			context.getInput().setCursorPos(1595, 250);
			for (double zoom : new double[] {4, 2, 1, 0.5, 0.25}) {
				context.runOnClient(mc -> {
					if (mc.screen instanceof MapScreen screen) {
						screen.lookAt(u, v, zoom, TileKey.SURFACE);
					}
				});
				context.waitTicks(40);
				GregedColonies.LOGGER.info("Create buried test: map drawn at zoom {}", zoom);
			}
			context.takeScreenshot("create-buried-map-zoomed-out");
			context.setScreen(() -> null);
			// The minimap as far out as it goes, from on top of the slab.
			context.runOnClient(mc -> Settings.get().zoomStep(0));
			context.waitTicks(60);
			context.takeScreenshot("create-buried-minimap");
			if (!context.computeOnClient(mc -> MapSession.get().crafts().has(id[0]))) {
				throw new AssertionError("The map lost the contraption");
			}
		} finally {
			context.runOnClient(mc -> Settings.get().zoomStep(savedZoom));
			FacingTest.turnTo(context, savedFacing);
		}
	}

	/**
	 * A z near {@code from} at which the bottom of the upside-down area lands well inside a map
	 * tile, so its top is two tiles further down whatever the craft's exact height. Elsewhere it
	 * can span a tile edge in a way that makes an empty list of tiles, which crashed nothing.
	 */
	private static int aligned(ClientGameTestContext context, int from) {
		return context.computeOnClient(mc -> {
			int ppu = Projection.PIXELS_PER_UNIT;
			for (int z = from; z < from + 400; z++) {
				int bottom = (int) Math.ceil(((z + 3) * Projection.sin() - SLAB * Projection.cos()) * ppu);
				int in = Math.floorMod(bottom - 1, Projection.TILE_PIXELS);
				if (in >= 200 && in <= 420) {
					return z;
				}
			}
			throw new AssertionError("No z near " + from + " puts the area inside a tile");
		});
	}

	private static boolean isContraption(Entity entity) {
		for (Class<?> c = entity.getClass(); c != null; c = c.getSuperclass()) {
			if (c.getName().equals(CONTRAPTION)) {
				return true;
			}
		}
		return false;
	}

	private static Object call(Object owner, String name) {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == 0) {
					m.setAccessible(true);
					try {
						return m.invoke(owner);
					} catch (ReflectiveOperationException e) {
						throw new AssertionError(owner.getClass().getName() + "." + name, e);
					}
				}
			}
		}
		throw new AssertionError("No " + name + " on " + owner.getClass().getName());
	}
}

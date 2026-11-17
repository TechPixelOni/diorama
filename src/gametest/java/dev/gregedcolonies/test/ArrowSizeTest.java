package dev.gregedcolonies.test;

import dev.gregedcolonies.MinimapHud;
import dev.gregedcolonies.Settings;
import dev.gregedcolonies.SettingsScreen;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The player arrow's size (opt-in: {@code arrow-size}): the arrow on the minimap and on the map at
 * the smallest, usual and biggest size, measured in the screenshot by its pink middle, and the
 * slider beside Arrow colour picking the biggest with a real click.
 */
final class ArrowSizeTest {
	private static final int LOOK_TAB = 4;
	/** Index of pink in {@link Settings#ARROW_COLORS}: a colour the land around the player doesn't have. */
	private static final int PINK = 6;

	private ArrowSizeTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("tp @a ~ ~ ~ 135 0");
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		int colour = context.computeOnClient(mc -> Settings.get().arrowColor);
		int sizeBefore = context.computeOnClient(mc -> Settings.get().minimapSize);
		Settings.Shape shapeBefore = context.computeOnClient(mc -> Settings.get().minimapShape);
		context.runOnClient(mc -> {
			Settings settings = Settings.get();
			if (settings.arrowSize != 100) {
				throw new AssertionError("The arrow size should start at 100%, not " + settings.arrowSize);
			}
			settings.arrowColor = PINK;
			settings.minimapSize = 128;
			settings.minimapShape = Settings.Shape.SQUARE;
		});
		context.getInput().resizeWindow(1920, 1080);
		GregedColoniesClientTest.setGuiScale(context, 3);
		// Long enough for the command's chat line to fade.
		context.waitTicks(220);

		int[] sizes = {Settings.MIN_ARROW_SIZE, 100, 150, Settings.MAX_ARROW_SIZE};
		int[] onMinimap = new int[sizes.length];
		int[] onMap = new int[sizes.length];
		for (int i = 0; i < sizes.length; i++) {
			int size = sizes[i];
			context.runOnClient(mc -> Settings.get().arrowSize = size);
			// The arrow's pictures are drawn on another thread; until then a flat one stands in.
			context.waitTicks(30);
			BufferedImage shot = read(context.takeScreenshot("arrow-size-minimap-" + size));
			int[] box = context.computeOnClient(mc -> MinimapHud.shown());
			if (box == null) {
				throw new AssertionError("No minimap at arrow size " + size + "%");
			}
			onMinimap[i] = pinkAcross(shot, (box[0] + box[2] / 2.0) * 3, (box[1] + box[2] / 2.0) * 3, 30 * 3, "the minimap at " + size + "%");
		}
		GregedColoniesClientTest.openMapOnPlayer(context);
		for (int i = 0; i < sizes.length; i++) {
			int size = sizes[i];
			context.runOnClient(mc -> Settings.get().arrowSize = size);
			context.waitTicks(30);
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(3);
			BufferedImage shot = read(context.takeScreenshot("arrow-size-map-" + size));
			onMap[i] = pinkAcross(shot, shot.getWidth() / 2.0, shot.getHeight() / 2.0, 30 * 3, "the map at " + size + "%");
		}
		context.setScreen(() -> null);
		for (int[] across : new int[][] {onMinimap, onMap}) {
			String where = across == onMinimap ? "minimap" : "map";
			// The pink middle is made of whole blocks, so it grows in steps: smaller, then about one and a half times and twice the usual.
			if (across[0] >= across[1] || across[2] * 10 < across[1] * 13 || across[3] * 10 < across[1] * 17 || across[3] * 10 > across[1] * 28) {
				throw new AssertionError("On the " + where + " the arrow's pink is " + across[0] + ", " + across[1] + ", " + across[2] + " and " + across[3]
					+ " screen pixels across at " + Settings.MIN_ARROW_SIZE + "%, 100%, 150% and 200%");
			}
		}

		// The slider beside Arrow colour: a real click at its right end picks the biggest.
		context.runOnClient(mc -> Settings.get().arrowSize = 100);
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.runOnClient(mc -> ((SettingsScreen) mc.screen).selectTab(LOOK_TAB));
		context.waitTicks(5);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("arrow-size-settings");
		GregedColoniesClientTest.clickAlong(context, "Arrow size: 100%", 0.98);
		if (context.computeOnClient(mc -> Settings.get().arrowSize) != Settings.MAX_ARROW_SIZE) {
			throw new AssertionError("Clicking the end of Arrow size picked " + context.computeOnClient(mc -> Settings.get().arrowSize) + "%");
		}
		context.takeScreenshot("arrow-size-settings-200");
		context.setScreen(() -> null);
		// The settings at the smallest GUI the game makes at 1280x720, where the tab has the least room.
		context.getInput().resizeWindow(1280, 720);
		context.waitTicks(10);
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.runOnClient(mc -> ((SettingsScreen) mc.screen).selectTab(LOOK_TAB));
		context.waitTicks(5);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("arrow-size-settings-1280x720");
		context.setScreen(() -> null);

		GregedColoniesClientTest.setGuiScale(context, guiScale);
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(10);
		context.runOnClient(mc -> {
			Settings settings = Settings.get();
			settings.arrowSize = 100;
			settings.arrowColor = colour;
			settings.minimapSize = sizeBefore;
			settings.minimapShape = shapeBefore;
			// The click saved its choice, and the next run starts from what's saved.
			settings.save();
		});
	}

	/** How far the arrow's pink reaches, across or down, whichever is more, within {@code reach} screen pixels of a point. */
	private static int pinkAcross(BufferedImage shot, double cx, double cy, int reach, String where) {
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
		for (int y = Math.max(0, (int) cy - reach); y < Math.min(shot.getHeight(), (int) cy + reach); y++) {
			for (int x = Math.max(0, (int) cx - reach); x < Math.min(shot.getWidth(), (int) cx + reach); x++) {
				int rgb = shot.getRGB(x, y);
				int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
				if (r > 150 && g < 110 && b > 100 && r - g > 80) {
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
			}
		}
		if (x1 < 0) {
			throw new AssertionError("No pink arrow on " + where);
		}
		if (x0 <= cx - reach || y0 <= cy - reach || x1 >= cx + reach - 1 || y1 >= cy + reach - 1) {
			throw new AssertionError("Pink reaches the edge of where the arrow is looked for on " + where + ": something else is pink there");
		}
		return Math.max(x1 - x0 + 1, y1 - y0 + 1);
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

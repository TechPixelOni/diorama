package dev.gregedcolonies.test;

import dev.gregedcolonies.MapSession;
import dev.gregedcolonies.Projection;
import dev.gregedcolonies.bake.TileChunks;
import dev.gregedcolonies.bake.TileKey;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Land claims on the server's web map, on a real server with FTB Chunks or Open Parties and Claims
 * installed on it and in the client ({@code --env GREGEDCOLONIES_REAL_SERVER=1 --mods ftb} or {@code
 * --mods opac}). The player claims a flat patch of grass east of spawn, with its middle chunk force
 * loaded, and a stepped stone hill 40 blocks high to the west (with Open Parties and Claims, as a
 * server claim), and a patch in the Nether. Then:
 *
 * <ul>
 * <li>{@code /claims.json}, which GregedColonies's own web server answers from memory, lists those chunks
 *     with the owner's name and colour, the middle as force loaded; a {@code claims.json} file left
 *     in the map's folder is deleted and never served;
 * <li>the file beside each picture says the pixels on the grass and on the hill's top show the chunks
 *     they are of (a flat outline at sea level would be two chunks off on the hilltop);
 * <li>the page is photographed over both, and the map in the game beside it;
 * <li>unclaiming the grass takes it out, and claims the mod hides from other players (an FTB team's
 *     claim visibility, Open Parties and Claims' claimsSynchronization) aren't there;
 * <li>with the Nether off the map ({@code map-off-dimensions}) its claims aren't listed (with Open
 *     Parties and Claims' {@code OWNED_ONLY}, the player's own Overworld claim neither), and with
 *     {@code web-map-claims=false} nothing answers and the map file doesn't offer claims;
 * <li>with {@code GREGEDCOLONIES_CLAIMS_BENCH=1}, what reading thousands of claims costs the server
 *     thread, side by side and each in a region of its own.
 * </ul>
 *
 * No claims file is ever in the map's folder. Photographs go into {@code screenshots/web-map-claims/}.
 */
final class WebMapClaimsTest {
	private static final String FOLDER = "minecraft_overworld";
	private static final String RED = "#e04040";
	/**
	 * The FTB server team that claims the hill. The console can set a server team's colour and claim
	 * visibility; a player's own team's settings are commands only its player may give.
	 */
	private static final String TEAM = "Homestead";
	/** An owner in the claims JSON, up to its name: the owner's id first. */
	private static final String OWNER = "\\{\"id\":\"[^\"]+\",";

	private WebMapClaimsTest() {
	}

	static void run(ClientGameTestContext context) {
		if (System.getenv("GREGEDCOLONIES_REAL_SERVER") == null) {
			throw new AssertionError("web-map-claims needs a real server: run it with --env GREGEDCOLONIES_REAL_SERVER=1");
		}
		boolean ftb = context.computeOnClient(mc -> dev.gregedcolonies.compat.FtbChunks.installed());
		boolean opac = context.computeOnClient(mc -> dev.gregedcolonies.compat.OpenPartiesAndClaims.installed());
		if (ftb == opac) {
			throw new AssertionError("web-map-claims needs one of FTB Chunks or Open Parties and Claims, in the client and on the server: run it with --mods ftb or --mods opac");
		}
		String mod = ftb ? "FTB Chunks" : "Open Parties and Claims";
		Path home = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("GREGEDCOLONIES_NODE"));
		Path config = home.resolve("config").resolve("gregedcolonies-server.properties");
		Path out = Path.of("screenshots", "web-map-claims");
		int port = freePort();
		String base = "web-map=true\nweb-map-port=" + port + "\nweb-map-address=127.0.0.1\nshare-land=generated\n";
		String page = "http://127.0.0.1:" + port + "/";
		HttpClient http = HttpClient.newHttpClient();
		List<String> wrong = new ArrayList<>();
		try {
			Files.createDirectories(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		// The land and the claims, with the web map off: the next start draws everything at once.
		write(config, "web-map=false\n");
		String me;
		String team = TEAM;
		int[] flat;
		int[] hill;
		try (RealServer server = RealServer.start(true)) {
			server.command("time set noon");
			server.command("gamerule doDaylightCycle false");
			server.join(context);
			me = context.computeOnClient(mc -> mc.player.getScoreboardName());
			server.command("op " + me);
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			int px = context.computeOnClient(mc -> mc.player.getBlockX()) >> 4;
			int pz = context.computeOnClient(mc -> mc.player.getBlockZ()) >> 4;
			// Three chunks by three each: grass four chunks east, the hill five west.
			flat = new int[] {px + 4, pz};
			hill = new int[] {px - 5, pz};
			int ground = build(context, server, flat, hill);
			flat = new int[] {flat[0], flat[1], ground};
			hill = new int[] {hill[0], hill[1], ground + 40};

			if (ftb) {
				server.command("ftbteams server create " + TEAM);
				team = ftbShortName(context, server, home);
				server.command("ftbteams server settings " + team + " ftbteams:color " + RED.toUpperCase(Locale.ROOT));
				server.command("ftbteams server settings " + team + " ftbchunks:claim_visibility public");
				// The grass the player's own team's, its middle force loaded; the hill the server team's.
				claimFtb(context, server, me, flat, true);
				server.command("ftbchunks admin claim_as " + team + " 16 " + (hill[0] * 16 + 8) + " " + (hill[1] * 16 + 8));
				// And a patch in the Nether.
				server.command("execute in minecraft:the_nether run tp " + me + " 8 100 8");
				context.waitFor(mc -> mc.level.dimension().location().getPath().equals("the_nether"), 1200);
				context.waitTicks(40);
				server.command("execute as " + me + " at " + me + " run ftbchunks claim 0");
			} else {
				claimOpac(context, server, me, flat, false);
				GregedColoniesClientTest.opacRequest(context, "requestAreaForceload", flat[0], flat[1], flat[0], flat[1], true, false);
				claimOpac(context, server, me, hill, true);
				server.command("execute in minecraft:the_nether run tp " + me + " 8 100 8");
				context.waitFor(mc -> mc.level.dimension().location().getPath().equals("the_nether"), 1200);
				context.waitTicks(40);
				GregedColoniesClientTest.opacRequest(context, "requestAreaClaim", 0, 0, 0, 0, false);
			}
			context.waitTicks(100);
			server.command("execute in minecraft:overworld run tp " + me + " " + (px * 16 + 8) + " " + (ground + 1) + " " + (pz * 16 + 8));
			context.waitFor(mc -> mc.level.dimension().location().getPath().equals("overworld"), 1200);
			context.waitTicks(60);
			RealServer.leave(context);
		}

		// A claims file an earlier build left, under two spellings: deleted or never served.
		Path folder = home.resolve("world").resolve("gregedcolonies-web");
		String planted = "{\"dimensions\":[{\"id\":\"minecraft:overworld\",\"owners\":[{\"name\":\"Planted\",\"color\":\"#00ff00\"}],\"chunks\":[0,0,0]}]}";
		write(folder.resolve("claims.json"), planted);
		write(folder.resolve("Claims.json"), planted);
		write(config, base);
		try (RealServer server = RealServer.start(true)) {
			waitForPass(context, http, port, server);
			server.join(context);
			String map = waitForText(context, http, port, "/map.json", "\"claims\":true", 400);
			String claims = waitForText(context, http, port, "/claims.json", "\"minecraft:the_nether\"", 400);
			expect(wrong, !claims.contains("Planted"), mod + ": the claims file left in the map's folder is served: " + cut(claims));
			expect(wrong, status(http, port, "/Claims.json") == 404, mod + ": a claims file is served under another spelling: " + cut(get(http, port, "/Claims.json")));
			noFile(wrong, folder, mod + ": with claims shown");
			check(wrong, claims, flat, true, mod + ": the grass");
			check(wrong, claims, hill, true, mod + ": the hill");
			expect(wrong, !listed(claims, "minecraft:overworld").contains(key(flat[0] - 2, flat[1])) && !listed(claims, "minecraft:overworld").contains(key(hill[0] + 2, hill[1])),
				mod + ": land between the claims is listed as claimed: " + cut(claims));
			expect(wrong, listed(claims, "minecraft:the_nether").contains(key(0, 0)), mod + ": the Nether's claim isn't listed: " + cut(claims));
			if (ftb) {
				expect(wrong, Pattern.compile(OWNER + "\"name\":\"" + TEAM + "\",\"color\":\"" + RED + "\"\\}").matcher(claims).find(), "FTB Chunks: the server team isn't listed by name in its colour: " + cut(claims));
				expect(wrong, Pattern.compile(OWNER + "\"name\":\"" + Pattern.quote(me) + "\",\"color\":\"#[0-9a-f]{6}\"\\}").matcher(claims).find(), "FTB Chunks: the player's team isn't listed by name: " + cut(claims));
				expect(wrong, Pattern.compile(OWNER + "\"name\":\"" + Pattern.quote(me) + "\",\"color\":\"#[0-9a-f]{6}\",\"forced\":true\\}").matcher(claims).find(), "FTB Chunks: the force loaded chunk isn't listed as force loaded: " + cut(claims));
			} else {
				expect(wrong, Pattern.compile(OWNER + "\"name\":\"" + Pattern.quote(me) + "'s Claim\",\"color\":\"#[0-9a-f]{6}\",\"titled\":true\\}").matcher(claims).find(),
					"Open Parties and Claims: the player's claim isn't titled as the mod words it: " + cut(claims));
				expect(wrong, claims.contains("\"name\":\"" + me + "'s Claim (Forceloadable)\""), "Open Parties and Claims: the force loadable chunk isn't titled so: " + cut(claims));
				expect(wrong, claims.contains("\"name\":\"Server Claim\""), "Open Parties and Claims: the server claim isn't titled so: " + cut(claims));
			}
			// The pixels on the grass and on the hilltop say they show the chunk they're of.
			lined(wrong, http, port, flat, "the grass");
			lined(wrong, http, port, hill, "the hilltop");
			// The third step up, 15 high, runs through the middle of the hill's south row of chunks.
			lined(wrong, http, port, new int[] {hill[0], hill[1] + 1, hill[2] - 25}, "the hill's south side");

			// Both claims on the page, and each up close.
			double middleU = (flat[0] + hill[0]) * 8 + 8;
			photograph(page + view(middleU, flat[1] * 16 + 8, flat[2] + 10, 4), out.resolve("1-both.png"));
			photograph(page + view(flat[0] * 16 + 8, flat[1] * 16 + 8, flat[2], 8), out.resolve("2-grass.png"));
			photograph(page + view(hill[0] * 16 + 8, hill[1] * 16 + 8, hill[2], 8), out.resolve("3-hill.png"));
			// Whose it is: the mouse over the hilltop, in the middle of the page; a tap on a phone on the grass's force loaded middle.
			photograph(page + view(hill[0] * 16 + 8, hill[1] * 16 + 8, hill[2], 8), out.resolve("3-hill-pointer.png"), "--hover", "640,328");
			photograph(page + view(flat[0] * 16 + 8, flat[1] * 16 + 8, flat[2], 4), out.resolve("2-grass-phone-tap.png"), "--phone", "--tap", "195,422");
			// The map in the game, over the same land.
			server.command("tp " + me + " " + (int) middleU + " " + (flat[2] + 1) + " " + (flat[1] * 16 + 8));
			context.waitTicks(100);
			GregedColoniesClientTest.openMapOnPlayer(context);
			// About as far out as the page's picture of both.
			double inGameV = Projection.v(flat[2] + 10, flat[1] * 16 + 8);
			context.runOnClient(mc -> {
				if (mc.screen instanceof dev.gregedcolonies.MapScreen screen) {
					screen.lookAt(middleU, inGameV, 1.5, TileKey.SURFACE);
				}
			});
			GregedColoniesClientTest.settle(context, 40);
			context.takeScreenshot("web-map-claims-in-game");
			context.setScreen(() -> null);

			// The grass unclaimed: off the page.
			if (ftb) {
				at(context, server, me, flat);
				server.command("execute as " + me + " at " + me + " run ftbchunks unload 0");
				server.command("execute as " + me + " at " + me + " run ftbchunks unclaim 16");
			} else {
				at(context, server, me, flat);
				GregedColoniesClientTest.opacRequest(context, "requestAreaUnclaim", flat[0] - 1, flat[1] - 1, flat[0] + 1, flat[1] + 1, false);
			}
			String unclaimed = waitForNo(context, http, port, key(flat[0] + 1, flat[1] + 1), wrong, mod + ": the unclaimed grass");
			check(wrong, unclaimed, flat, false, mod + ": the unclaimed grass");
			check(wrong, unclaimed, hill, true, mod + ": the hill after the grass was unclaimed");
			photograph(page + view(flat[0] * 16 + 8, flat[1] * 16 + 8, flat[2], 8), out.resolve("4-grass-unclaimed.png"));
			if (ftb) {
				// A team that hides its claims from other teams: off the page, and back once it shows them.
				server.command("ftbteams server settings " + team + " ftbchunks:claim_visibility private");
				String hidden = waitForNo(context, http, port, key(hill[0], hill[1]), wrong, "FTB Chunks: a team's claims hidden from other teams");
				expect(wrong, !hidden.contains(TEAM), "FTB Chunks: a team hiding its claims is still named: " + cut(hidden));
				noFile(wrong, folder, "FTB Chunks: with a team's claims hidden");
				server.command("ftbteams server settings " + team + " ftbchunks:claim_visibility public");
				waitForListed(context, http, port, key(hill[0], hill[1]), "FTB Chunks: the hill once its team shows its claims again");
			}
			expect(wrong, map.contains("\"claims\":true"), "the map file doesn't offer claims");
			if (opac) {
				// The player's own claim in the Overworld again, which OWNED_ONLY takes off the page below.
				GregedColoniesClientTest.opacRequest(context, "requestAreaClaim", flat[0], flat[1], flat[0], flat[1], false);
				waitForListed(context, http, port, key(flat[0], flat[1]), "Open Parties and Claims: the player's claim made again");
			}
			if (System.getenv("GREGEDCOLONIES_CLAIMS_BENCH") != null) {
				bench(context, server, http, port, ftb, team, hill);
			}
			RealServer.leave(context);
		}

		// The Nether off the map: none of its claims on the page. With Open Parties and Claims sending
		// each player only their own claims (and the server's), only the server's.
		write(config, base + "map-off-dimensions=minecraft:the_nether\n");
		if (opac) {
			setOpacSync(home, "OWNED_ONLY");
		}
		try (RealServer server = RealServer.start(true)) {
			String claims = waitForText(context, http, port, "/claims.json", "minecraft:overworld", 600);
			expect(wrong, !claims.contains("minecraft:the_nether"), mod + ": the Nether is off the map and its claims are listed: " + cut(claims));
			if (opac) {
				expect(wrong, claims.contains("\"Server Claim\"") && !claims.contains("'s Claim") && !listed(claims, "minecraft:overworld").contains(key(flat[0], flat[1])),
					"Open Parties and Claims: with claimsSynchronization OWNED_ONLY, the page should have only the server's claims: " + cut(claims));
				check(wrong, claims, hill, true, "Open Parties and Claims: the server's claim on the hill, with OWNED_ONLY");
				setOpacSync(home, "ALL");
			} else {
				check(wrong, claims, hill, true, "FTB Chunks: the hill, with the Nether off the map");
			}
			noFile(wrong, folder, mod + ": with the Nether off the map");
		}

		// Turned off by the owner: no claims file, and the page isn't told of one.
		write(config, base + "web-map-claims=false\n");
		try (RealServer server = RealServer.start(true)) {
			String map = waitForText(context, http, port, "/map.json", "\"players\":", 600);
			context.waitTicks(100);
			expect(wrong, !map.contains("\"claims\""), "with web-map-claims=false, the map file offers claims: " + cut(map));
			String off = get(http, port, "/claims.json");
			expect(wrong, status(http, port, "/claims.json") == 404, "with web-map-claims=false, claims.json is answered: " + cut(off));
			expect(wrong, !off.contains(TEAM) && !off.contains(me) && !off.contains("Server Claim"), "with web-map-claims=false, claims.json names owners: " + cut(off));
			noFile(wrong, folder, "with web-map-claims=false");
			photograph(page + view(hill[0] * 16 + 8, hill[1] * 16 + 8, hill[2], 8), out.resolve("5-claims-off.png"));
		}

		try {
			Files.deleteIfExists(config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("The web map's claims are wrong:\n" + String.join("\n", wrong));
		}
	}

	/**
	 * A grass patch of three chunks by three round {@code flat}, with clear air over it, and a stepped
	 * stone hill on the same footprint round {@code hill}, eight steps of five blocks, 40 high. Returns
	 * the height of the grass's top.
	 */
	private static int build(ClientGameTestContext context, RealServer server, int[] flat, int[] hill) {
		int y = 0;
		for (int[] area : new int[][] {flat, hill}) {
			int x0 = (area[0] - 1) * 16;
			int z0 = (area[1] - 1) * 16;
			server.command("forceload add " + x0 + " " + z0 + " " + (x0 + 47) + " " + (z0 + 47));
			int cx = area[0] * 16 + 8;
			int cz = area[1] * 16 + 8;
			y = Math.max(y, context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz)));
		}
		int top = Math.max(72, y);
		context.waitTicks(40);
		for (int[] area : new int[][] {flat, hill}) {
			int x0 = (area[0] - 1) * 16;
			int z0 = (area[1] - 1) * 16;
			// Fill takes at most 32768 blocks at once: a chunk's column at a time.
			for (int x = x0; x < x0 + 48; x += 16) {
				for (int z = z0; z < z0 + 48; z += 16) {
					server.command("fill " + x + " " + top + " " + z + " " + (x + 15) + " " + (top + 60) + " " + (z + 15) + " air");
					server.command("fill " + x + " " + (top - 8) + " " + z + " " + (x + 15) + " " + (top - 1) + " " + (z + 15) + " dirt");
					server.command("fill " + x + " " + (top - 1) + " " + z + " " + (x + 15) + " " + (top - 1) + " " + (z + 15) + " grass_block");
				}
			}
		}
		int x0 = (hill[0] - 1) * 16;
		int z0 = (hill[1] - 1) * 16;
		for (int step = 0; step < 8; step++) {
			int in = step * 3;
			server.command("fill " + (x0 + in) + " " + (top + step * 5) + " " + (z0 + in) + " " + (x0 + 47 - in) + " " + (top + step * 5 + 4) + " " + (z0 + 47 - in) + " stone");
		}
		context.waitTicks(60);
		return top;
	}

	/**
	 * What commands call the FTB server team just made: its name, then # and the start of its id,
	 * which names its file once the server has saved it.
	 */
	private static String ftbShortName(ClientGameTestContext context, RealServer server, Path home) {
		server.commandAndWait("save-all flush", "Saved the game", 300);
		context.waitTicks(20);
		try (java.util.stream.Stream<Path> files = Files.walk(home.resolve("world"), 5)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				String name = file.getFileName().toString();
				if (name.endsWith(".snbt") && file.getParent().getFileName().toString().equals("server") && Files.readString(file).contains(TEAM)) {
					return TEAM + "#" + name.substring(0, 8);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		throw new AssertionError("FTB Teams saved no file for the server team " + TEAM + " under " + home.resolve("world"));
	}

	/** The player's FTB team claims the three chunks by three round {@code area}, the middle force loaded if asked. */
	private static void claimFtb(ClientGameTestContext context, RealServer server, String me, int[] area, boolean forceLoadMiddle) {
		at(context, server, me, area);
		server.command("execute as " + me + " at " + me + " run ftbchunks claim 16");
		if (forceLoadMiddle) {
			server.command("execute as " + me + " at " + me + " run ftbchunks load 0");
		}
		context.waitTicks(20);
	}

	/** The player claims the three chunks by three round {@code area}, through Open Parties and Claims' client API, as the server if asked. */
	private static void claimOpac(ClientGameTestContext context, RealServer server, String me, int[] area, boolean byServer) {
		at(context, server, me, area);
		context.waitTicks(20);
		GregedColoniesClientTest.opacRequest(context, "requestAreaClaim", area[0] - 1, area[1] - 1, area[0] + 1, area[1] + 1, byServer);
		context.waitTicks(60);
	}

	/**
	 * Teleports the player to the middle of the chunk {@code area} names and waits until the client
	 * is there: a busy server can read several console commands at once well after they were given,
	 * and a claim asked for from the wrong place is refused as too far.
	 */
	private static void at(ClientGameTestContext context, RealServer server, String me, int[] area) {
		server.command("tp " + me + " " + (area[0] * 16 + 8) + " " + (area[2] + 1) + " " + (area[1] * 16 + 8));
		context.waitFor(mc -> mc.player != null && mc.player.blockPosition().getX() >> 4 == area[0] && mc.player.blockPosition().getZ() >> 4 == area[1], 2400);
		context.waitTicks(20);
	}

	/** Sets Open Parties and Claims' claimsSynchronization in the server's config, for its next start. */
	private static void setOpacSync(Path home, String mode) {
		// In the world's serverconfig folder or the server's config folder, by loader and version.
		Path file;
		try (java.util.stream.Stream<Path> files = Files.walk(home, 3)) {
			file = files.filter(f -> f.getFileName().toString().equals("openpartiesandclaims-server.toml")).findFirst()
				.orElseThrow(() -> new AssertionError("No openpartiesandclaims-server.toml under " + home));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		try {
			String text = Files.readString(file);
			String changed = text.replaceAll("claimsSynchronization\\s*=\\s*\"[A-Z_]+\"", "claimsSynchronization = \"" + mode + "\"");
			if (changed.equals(text) && !text.contains("\"" + mode + "\"")) {
				throw new AssertionError("No claimsSynchronization in " + file);
			}
			Files.writeString(file, changed);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	/** The chunks claims.json lists in a dimension, as {@link #key}; its list is x, z, owner, x, z, owner... */
	private static java.util.Set<Long> listed(String claims, String dimension) {
		java.util.Set<Long> out = new java.util.HashSet<>();
		java.util.regex.Matcher m = Pattern.compile("\\{\"id\":\"" + Pattern.quote(dimension) + "\",\"owners\":\\[.*?\\],\"chunks\":\\[([-0-9,]*)\\]").matcher(claims);
		if (m.find() && !m.group(1).isEmpty()) {
			String[] numbers = m.group(1).split(",");
			for (int i = 0; i + 2 < numbers.length; i += 3) {
				out.add(key(Integer.parseInt(numbers[i]), Integer.parseInt(numbers[i + 1])));
			}
		}
		return out;
	}

	/** Says in {@code wrong} unless the three chunks by three round {@code area} are all listed in the Overworld, or all not. */
	private static void check(List<String> wrong, String claims, int[] area, boolean listed, String what) {
		java.util.Set<Long> chunks = listed(claims, "minecraft:overworld");
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				boolean found = chunks.contains(key(area[0] + dx, area[1] + dz));
				if (found != listed) {
					wrong.add(what + ": chunk " + (area[0] + dx) + ", " + (area[1] + dz) + (listed ? " isn't listed: " : " is listed: ") + cut(claims));
					return;
				}
			}
		}
	}

	/**
	 * Says in {@code wrong} unless the file beside the picture says the pixel showing the middle of
	 * the top of the block at chunk {@code at}'s middle, {@code at[2]} high, shows that chunk.
	 */
	private static void lined(List<String> wrong, HttpClient http, int port, int[] at, String what) {
		int x = at[0] * 16 + 8;
		int z = at[1] * 16 + 8;
		double u = x + 0.5;
		double v = Projection.v(at[2], z + 0.5);
		int tu = Math.floorDiv((int) Math.floor(u), Projection.TILE_UNITS);
		int tv = Math.floorDiv((int) Math.floor(v), Projection.TILE_UNITS);
		TileKey key = new TileKey(TileKey.SURFACE, tu, tv);
		int column = (int) Math.floor(u - key.minU());
		int row = (int) Math.floor((v - key.minV()) * Projection.PIXELS_PER_UNIT);
		byte[] png = bytes(http, port, "/" + FOLDER + "/0/" + tu + "_" + tv + ".png");
		if (png == null) {
			wrong.add(what + ": no chunks file beside picture " + tu + "_" + tv);
			return;
		}
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
			int value = image.getRaster().getSample(column, row, 0);
			int shown = value == 0 ? Integer.MIN_VALUE : TileChunks.base(key, row) + value - 128;
			// Where a flat outline at sea level would put the pixel: the row's crossing of height 64.
			int flat = TileChunks.base(key, row);
			dev.gregedcolonies.GregedColonies.LOGGER.info("web-map-claims: {} at {} {} {}, picture {}_{} column {} row {}: value {}, chunk z {} (its own {}; at height 64 it would be {}); chunks file {} bytes",
				what, x, at[2], z, tu, tv, column, row, value, shown, at[1], flat, png.length);
			if (shown != at[1]) {
				wrong.add(what + ": the pixel showing " + x + " " + at[2] + " " + z + " says it shows chunk z " + shown + ", not " + at[1]);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Photographs the page; a photograph that couldn't be taken fails the test. */
	private static void photograph(String address, Path file, String... options) {
		if (!ClientWebMapTest.photograph(address, file, options)) {
			throw new AssertionError("No photograph of " + address + " was taken (no Chromium on this machine?)");
		}
	}

	/** Says in {@code wrong} if a claims file is in the map's folder: claims are only ever in memory. */
	private static void noFile(List<String> wrong, Path folder, String when) {
		for (String name : new String[] {"claims.json", "claims.json.part"}) {
			if (Files.exists(folder.resolve(name))) {
				wrong.add(when + ": there's a " + name + " in the map's folder " + folder);
			}
		}
	}

	/**
	 * What reading many claims costs the server thread, from the status command: 4,096 chunks side by
	 * side, then 2,000 chunks each in a region of its own, all the server's (an FTB server team's).
	 * Logged; each set is unclaimed after.
	 */
	private static void bench(ClientGameTestContext context, RealServer server, HttpClient http, int port, boolean ftb, String team, int[] hill) {
		int x0 = hill[0] * 16 - 3000;
		int z0 = hill[1] * 16;
		// Side by side: 64 by 64 chunks.
		if (ftb) {
			server.command("ftbchunks admin claim_as " + team + " 512 " + x0 + " " + z0);
		} else {
			server.command("oclaims server claim in minecraft:overworld " + (x0 - 512) + " " + (z0 - 512) + " " + (x0 + 511) + " " + (z0 + 511) + " anyway");
		}
		waitForListed(context, http, port, key((x0 + 500) >> 4, (z0 + 500) >> 4), "4,096 chunks claimed side by side");
		benchReads(context, server, "4,096 chunks side by side");
		if (ftb) {
			server.command("ftbchunks admin unclaim_as " + team + " 512 " + x0 + " " + z0);
		} else {
			server.command("oclaims server unclaim in minecraft:overworld " + (x0 - 512) + " " + (z0 - 512) + " " + (x0 + 511) + " " + (z0 + 511) + " anyway");
		}
		List<String> wrong = new ArrayList<>();
		waitForNo(context, http, port, key((x0 + 500) >> 4, (z0 + 500) >> 4), wrong, "4,096 chunks unclaimed");
		// Each in a region of its own: 2,000 chunks 512 blocks apart, a field of 50 by 40 regions.
		List<String> commands = new ArrayList<>();
		int last = 0;
		for (int i = 0; i < 2000; i++) {
			int x = x0 - 40_000 + (i % 50) * 512 + 8;
			int z = z0 + (i / 50) * 512 + 8;
			commands.add(ftb ? "ftbchunks admin claim_as " + team + " 0 " + x + " " + z
				: "oclaims server claim in minecraft:overworld " + x + " " + z + " " + x + " " + z + " anyway");
			if (commands.size() == 250 || i == 1999) {
				server.command(String.join("\n", commands));
				commands.clear();
				context.waitTicks(20);
			}
			last = i;
		}
		int lastX = x0 - 40_000 + (last % 50) * 512 + 8;
		int lastZ = z0 + (last / 50) * 512 + 8;
		waitForListed(context, http, port, key(lastX >> 4, lastZ >> 4), "2,000 chunks each in a region of its own");
		String claims = get(http, port, "/claims.json");
		dev.gregedcolonies.GregedColonies.LOGGER.info("web-map-claims bench: {} chunks listed in the Overworld with 2,000 apart", listed(claims, "minecraft:overworld").size());
		benchReads(context, server, "2,000 chunks each in a region of its own");
		wrong.forEach(w -> dev.gregedcolonies.GregedColonies.LOGGER.warn("web-map-claims bench: {}", w));
	}

	/** A few reads' costs as the status command says them, the first ones with the code still cold. */
	private static void benchReads(ClientGameTestContext context, RealServer server, String what) {
		for (int i = 0; i < 8; i++) {
			context.waitTicks(i < 4 ? 80 : 300);
			server.command("gregedcolonies-web status");
			context.waitTicks(20);
			String console = server.console();
			int said = console.lastIndexOf("Web map: ");
			dev.gregedcolonies.GregedColonies.LOGGER.info("web-map-claims bench: with {} the server says {}", what, said < 0 ? "nothing" : console.substring(said, console.indexOf('\n', said)));
		}
	}

	/** The page's address for a view centred on a block's top. */
	private static String view(double x, double z, int y, double scale) {
		return String.format(Locale.ROOT, "#dim=minecraft:overworld&u=%d&v=%d&s=%.3f", Math.round(x), Math.round(Projection.v(y, z)), scale);
	}

	private static void expect(List<String> wrong, boolean right, String otherwise) {
		if (!right) {
			wrong.add(otherwise);
		}
	}

	/** Waits until a path's text has {@code part} in it, and returns the text. */
	private static String waitForText(ClientGameTestContext context, HttpClient http, int port, String path, String part, int ticks) {
		String[] text = {""};
		int[] asked = {0};
		try {
			context.waitFor(mc -> {
				if (asked[0]++ % 10 != 0 || status(http, port, path) != 200) {
					return false;
				}
				text[0] = get(http, port, path);
				return text[0].contains(part);
			}, ticks);
		} catch (AssertionError e) {
			throw new AssertionError(path + " never had " + part + " in it: " + cut(text[0]), e);
		}
		return text[0];
	}

	/** Waits until claims.json no longer lists an Overworld chunk; says so in {@code wrong} if it keeps it. Returns the text. */
	private static String waitForNo(ClientGameTestContext context, HttpClient http, int port, long chunk, List<String> wrong, String what) {
		int[] asked = {0};
		try {
			context.waitFor(mc -> asked[0]++ % 10 == 0 && status(http, port, "/claims.json") == 200 && !listed(get(http, port, "/claims.json"), "minecraft:overworld").contains(chunk), 400);
		} catch (AssertionError e) {
			wrong.add(what + " is still listed: " + cut(get(http, port, "/claims.json")));
		}
		return get(http, port, "/claims.json");
	}

	/** Waits until claims.json lists an Overworld chunk. */
	private static void waitForListed(ClientGameTestContext context, HttpClient http, int port, long chunk, String what) {
		int[] asked = {0};
		try {
			context.waitFor(mc -> asked[0]++ % 10 == 0 && listed(get(http, port, "/claims.json"), "minecraft:overworld").contains(chunk), 400);
		} catch (AssertionError e) {
			throw new AssertionError(what + " never came back: " + cut(get(http, port, "/claims.json")), e);
		}
	}

	/** Waits until the web map has finished a pass and lists pictures of the Overworld. */
	private static void waitForPass(ClientGameTestContext context, HttpClient http, int port, RealServer server) {
		int[] asked = {0};
		context.waitFor(mc -> {
			if (asked[0]++ % 40 != 0) {
				return false;
			}
			server.command("gregedcolonies-web status");
			String console = server.console();
			int said = console.lastIndexOf("Web map: ");
			return said >= 0 && console.startsWith("Web map: up to date", said) && Pattern.compile("\"tiles\":\\[\\[-?\\d").matcher(get(http, port, "/map.json")).find();
		}, 20 * 60 * 10);
	}

	private static String cut(String json) {
		return json.length() > 600 ? json.substring(0, 600) + "..." : json;
	}

	private static void write(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The HTTP status of a path, or -1 while nothing answers. */
	private static int status(HttpClient http, int port, String path) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (IOException e) {
			return -1;
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static String get(HttpClient http, int port, String path) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString()).body();
		} catch (IOException e) {
			return "";
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] bytes(HttpClient http, int port, String path) {
		try {
			HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofByteArray());
			return response.statusCode() == 200 ? response.body() : null;
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

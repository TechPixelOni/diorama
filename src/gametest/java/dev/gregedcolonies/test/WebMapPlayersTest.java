package dev.gregedcolonies.test;

import dev.gregedcolonies.MapSession;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * The players on the server's web map (opt-in: {@code web-map-players}, on a real server:
 * {@code --env GREGEDCOLONIES_REAL_SERVER=1}). Two players are online, one of them a vanilla client: the
 * page's {@code players.json} names both where they stand, and stops naming the one who becomes
 * invisible, a spectator, sneaking, or leaves, while still naming the other. It is answered from
 * memory, and no file in the map's folder says where players are. With {@code
 * web-map-players=false} or {@code radar-allowed=false} nothing answers at that address. Photographs the
 * page into {@code screenshots/web-map-players/}.
 */
final class WebMapPlayersTest {
	private static final String OTHER = "Alice";

	private WebMapPlayersTest() {
	}

	static void run(ClientGameTestContext context) {
		if (System.getenv("GREGEDCOLONIES_REAL_SERVER") == null) {
			throw new AssertionError("web-map-players needs a real server: run it with --env GREGEDCOLONIES_REAL_SERVER=1");
		}
		Path config = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("GREGEDCOLONIES_NODE"), "config", "gregedcolonies-server.properties");
		Path out = Path.of("screenshots", "web-map-players");
		int port = freePort();
		String base = "web-map=true\nweb-map-port=" + port + "\nweb-map-address=127.0.0.1\nshare-land=visited\n";
		String page = "http://127.0.0.1:" + port + "/";
		HttpClient http = HttpClient.newHttpClient();
		List<String> wrong = new ArrayList<>();
		try {
			Files.createDirectories(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		// The player spends a while where they join; the server saves the land as it stops.
		write(config, base);
		try (RealServer server = RealServer.start(true)) {
			server.command("time set noon");
			server.join(context);
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			context.waitTicks(300);
			RealServer.leave(context);
		}

		String me;
		try (RealServer server = RealServer.start(true)) {
			// The land from before, drawn.
			waitForPass(context, http, port, server);
			server.join(context);
			me = context.computeOnClient(mc -> mc.player.getScoreboardName());
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			// Before anything is drawn the page says so, and whether it shows players.
			String map = waitForText(context, http, port, "/map.json", "\"players\":", 400);
			expect(wrong, map.contains("\"players\":true"), "with nothing set, the map file doesn't say it shows players: " + cut(map));
			String alone = waitForText(context, http, port, "/players.json", "\"name\":\"" + me + "\"", 400);
			expect(wrong, alone.contains("\"dimension\":\"minecraft:overworld\""), "the player isn't in the Overworld on the page: " + alone);
			int[] at = context.computeOnClient(mc -> new int[] {mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ()});
			expect(wrong, alone.contains("\"x\":" + at[0] + ",\"y\":" + at[1] + ",\"z\":" + at[2]), "the player is at " + at[0] + ", " + at[1] + ", " + at[2] + " and the page says " + alone);

			// A second player, without GregedColonies, twenty blocks east and looking another way.
			server.vanillaPlayerJoins(OTHER);
			server.command("tp " + OTHER + " " + (at[0] + 20) + " " + at[1] + " " + (at[2] + 6) + " 90 0");
			server.command("tp " + me + " " + at[0] + " " + at[1] + " " + at[2] + " -135 0");
			String both = waitForText(context, http, port, "/players.json", "\"name\":\"" + OTHER + "\",\"dimension\":\"minecraft:overworld\",\"x\":" + (at[0] + 20), 1200);
			expect(wrong, both.contains("\"name\":\"" + me + "\""), "with two players online the page names only one: " + both);

			// The page, on the two of them.
			Matcher place = Pattern.compile("\"name\":\"" + Pattern.quote(me) + "\"[^}]*\"u\":(-?[0-9.]+),\"v\":(-?[0-9.]+)").matcher(get(http, port, "/players.json"));
			String view = place.find() ? "#dim=minecraft:overworld&u=" + Math.round(Double.parseDouble(place.group(1)) + 10) + "&v=" + Math.round(Double.parseDouble(place.group(2))) + "&s=6" : "";
			ClientWebMapTest.photograph(page + view, out.resolve("1-two-players.png"));
			ClientWebMapTest.photograph(page, out.resolve("2-whole-map.png"));

			// Invisible, then a spectator: off the page each time, and back after.
			server.command("effect give " + OTHER + " minecraft:invisibility 600 0 true");
			waitForNo(context, http, port, OTHER, "invisible", wrong);
			expect(wrong, get(http, port, "/players.json").contains("\"name\":\"" + me + "\""), "with " + OTHER + " invisible, " + me + " is off the page too: " + get(http, port, "/players.json"));
			ClientWebMapTest.photograph(page + view, out.resolve("3-one-invisible.png"));
			server.command("effect clear " + OTHER);
			waitForText(context, http, port, "/players.json", "\"name\":\"" + OTHER + "\"", 400);
			server.command("gamemode spectator " + OTHER);
			waitForNo(context, http, port, OTHER, "a spectator", wrong);
			expect(wrong, get(http, port, "/players.json").contains("\"name\":\"" + me + "\""), "with " + OTHER + " spectating, " + me + " is off the page too: " + get(http, port, "/players.json"));
			server.command("gamemode creative " + OTHER);
			waitForText(context, http, port, "/players.json", "\"name\":\"" + OTHER + "\"", 400);
			// Sneaking, by the player's own key.
			net.minecraft.client.KeyMapping sneak = context.computeOnClient(mc -> mc.options.keyShift);
			context.getInput().holdKey(sneak);
			waitForNo(context, http, port, me, "sneaking", wrong);
			expect(wrong, get(http, port, "/players.json").contains("\"name\":\"" + OTHER + "\""), "with " + me + " sneaking, " + OTHER + " is off the page too: " + get(http, port, "/players.json"));
			// Nothing about players is ever a file in the map's folder.
			Path folder = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("GREGEDCOLONIES_NODE"), "world", "gregedcolonies-web");
			expect(wrong, Files.isRegularFile(folder.resolve("map.json")), "the web map's folder isn't " + folder);
			expect(wrong, !Files.exists(folder.resolve("players.json")) && !Files.exists(folder.resolve("players.json.part")), "the web map wrote where players are into " + folder);
			context.getInput().releaseKey(sneak);
			waitForText(context, http, port, "/players.json", "\"name\":\"" + me + "\"", 400);
			// Gone from the server, gone from the page.
			server.vanillaPlayerLeaves(OTHER);
			waitForNo(context, http, port, OTHER, "offline", wrong);
			expect(wrong, get(http, port, "/players.json").contains(me), "with " + OTHER + " gone, " + me + " is off the page too: " + get(http, port, "/players.json"));
			// A file of that name in the folder, left by anything, isn't served by any spelling of its address.
			write(folder.resolve("players.json"), "{\"players\":[{\"name\":\"Ghost\"}]}");
			for (String path : new String[] {"/players.json", "/players.json/", "/Players.json", "/./players.json", "/players.json?x=1"}) {
				expect(wrong, !get(http, port, path).contains("Ghost"), path + " serves a players file from the folder: " + get(http, port, path));
			}
			try {
				Files.deleteIfExists(folder.resolve("players.json"));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			RealServer.leave(context);
		}

		// Turned off by the owner: nothing answers where players were.
		write(config, base + "web-map-players=false\n");
		try (RealServer server = RealServer.start(true)) {
			server.join(context);
			String map = waitForText(context, http, port, "/map.json", "\"players\":", 400);
			context.waitTicks(60);
			expect(wrong, map.contains("\"players\":false"), "with web-map-players=false, the map file says it shows players: " + cut(map));
			expect(wrong, status(http, port, "/players.json") == 404, "with web-map-players=false, players.json is served: " + get(http, port, "/players.json"));
			ClientWebMapTest.photograph(page, out.resolve("4-players-off.png"));
			RealServer.leave(context);
		}

		// No players or mobs on the maps in the game: none on the page either.
		write(config, base + "radar-allowed=false\n");
		try (RealServer server = RealServer.start(true)) {
			server.join(context);
			String map = waitForText(context, http, port, "/map.json", "\"players\":", 400);
			context.waitTicks(60);
			expect(wrong, map.contains("\"players\":false"), "with radar-allowed=false, the map file says it shows players: " + cut(map));
			expect(wrong, status(http, port, "/players.json") == 404, "with radar-allowed=false, players.json is served: " + get(http, port, "/players.json"));
			RealServer.leave(context);
		}

		try {
			Files.deleteIfExists(config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("The web map's players are wrong:\n" + String.join("\n", wrong));
		}
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

	/** Waits for a player to be off the page; says so in {@code wrong} if they stay on it. */
	private static void waitForNo(ClientGameTestContext context, HttpClient http, int port, String name, String why, List<String> wrong) {
		int[] asked = {0};
		try {
			context.waitFor(mc -> asked[0]++ % 10 == 0 && status(http, port, "/players.json") == 200 && !get(http, port, "/players.json").contains("\"name\":\"" + name + "\""), 200);
		} catch (AssertionError e) {
			wrong.add(name + " is " + why + " and still on the page: " + get(http, port, "/players.json"));
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
		return json.length() > 300 ? json.substring(0, 300) + "..." : json;
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
			throw new UncheckedIOException(e);
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

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
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * The markers an operator places on the server's web map (opt-in: {@code web-map-markers}, on a
 * real server: {@code --env GREGEDCOLONIES_REAL_SERVER=1}). From the console: markers are added, one is
 * moved by adding it again and one removed, and the page's {@code markers.json} says so each
 * time. After a restart they're still there, and with the End in {@code map-off-dimensions} the
 * End's marker is off the page, still listed for the operator, and no new one can be placed
 * there. Photographs the page into {@code screenshots/web-map-markers/}.
 */
final class WebMapMarkersTest {
	private WebMapMarkersTest() {
	}

	static void run(ClientGameTestContext context) {
		if (System.getenv("GREGEDCOLONIES_REAL_SERVER") == null) {
			throw new AssertionError("web-map-markers needs a real server: run it with --env GREGEDCOLONIES_REAL_SERVER=1");
		}
		Path config = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("GREGEDCOLONIES_NODE"), "config", "gregedcolonies-server.properties");
		Path out = Path.of("screenshots", "web-map-markers");
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
		int[] at;
		try (RealServer server = RealServer.start(true)) {
			server.command("time set noon");
			server.join(context);
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			at = context.computeOnClient(mc -> new int[] {mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ()});
			context.waitTicks(300);
			RealServer.leave(context);
		}

		try (RealServer server = RealServer.start(true)) {
			waitForPass(context, http, port, server);
			expect(wrong, get(http, port, "/markers.json").equals("{\"markers\":[]}"), "before any marker is placed, markers.json is " + get(http, port, "/markers.json"));
			place(server, "minecraft:overworld", at[0], at[1], at[2], "Spawn Town");
			place(server, "minecraft:overworld", at[0] + 40, at[1], at[2] + 12, "Mine");
			place(server, "minecraft:overworld", at[0] - 30, at[1], at[2] - 20, "Old camp");
			place(server, "minecraft:the_end", 0, 80, 0, "Dragon");
			String four = waitForText(context, http, port, "/markers.json", "\"name\":\"Dragon\"", 2400);
			expect(wrong, four.contains(marker("Spawn Town", "minecraft:overworld", at[0], at[1], at[2])) && four.contains(marker("Mine", "minecraft:overworld", at[0] + 40, at[1], at[2] + 12))
				&& four.contains(marker("Old camp", "minecraft:overworld", at[0] - 30, at[1], at[2] - 20)) && four.contains(marker("Dragon", "minecraft:the_end", 0, 80, 0)), "four markers placed, and markers.json is " + four);
			String view = "#dim=minecraft:overworld&u=" + (at[0] + 10) + "&v=" + Math.round(at[2] * 0.866 - at[1] * 0.5) + "&s=5";
			ClientWebMapTest.photograph(page + view, out.resolve("1-three-markers.png"));

			// Added again under its name, in another case: moved, not doubled. Then one taken off.
			place(server, "minecraft:overworld", at[0] + 12, at[1], at[2] - 8, "spawn town");
			String moved = waitForText(context, http, port, "/markers.json", marker("Spawn Town", "minecraft:overworld", at[0] + 12, at[1], at[2] - 8), 2400);
			expect(wrong, count(moved, "pawn") == 1, "Spawn Town was added again to move it, and markers.json is " + moved);
			server.command("gregedcolonies-web marker remove Old camp");
			server.command("gregedcolonies-web marker remove Nowhere");
			waitFor(context, http, port, "\"name\":\"Old camp\"", false, wrong);
			context.waitTicks(20);
			String console = server.console();
			expect(wrong, console.contains("Marked Spawn Town on the web map") && console.contains("Moved Spawn Town on the web map") && console.contains("Took Old camp off the web map")
				&& console.contains("The web map has no marker named Nowhere"), "the console doesn't say what each marker command did");
			ClientWebMapTest.photograph(page + view, out.resolve("2-moved-and-removed.png"));
		}

		// After a restart they're as they were. The End off: its marker isn't on the page, and none can be placed there.
		write(config, base + "map-off-dimensions=minecraft:the_end\n");
		try (RealServer server = RealServer.start(true)) {
			String kept = waitForText(context, http, port, "/markers.json", "\"name\":\"Mine\"", 2400);
			expect(wrong, kept.contains(marker("Spawn Town", "minecraft:overworld", at[0] + 12, at[1], at[2] - 8)) && !kept.contains("Old camp"), "after a restart markers.json is " + kept);
			expect(wrong, !kept.contains("Dragon"), "with the End in map-off-dimensions, markers.json still has the End's marker: " + kept);
			place(server, "minecraft:the_end", 10, 80, 10, "Gateway");
			server.command("gregedcolonies-web marker list");
			context.waitTicks(40);
			String console = server.console();
			expect(wrong, console.contains("The web map doesn't show this dimension"), "a marker could be placed in the End with the map off there");
			expect(wrong, console.contains("Dragon: 0, 80, 0 in minecraft:the_end (not on the page"), "the list doesn't say the End's marker is kept but off the page");
			expect(wrong, !get(http, port, "/markers.json").contains("Gateway"), "markers.json has a marker placed in a dimension with the map off");
		}

		// The End on again: its marker is back.
		write(config, base);
		try (RealServer server = RealServer.start(true)) {
			waitForText(context, http, port, "/markers.json", "\"name\":\"Dragon\"", 2400);
			for (String name : new String[] {"Spawn Town", "Mine", "Dragon"}) {
				server.command("gregedcolonies-web marker remove " + name);
			}
			waitFor(context, http, port, "\"name\"", false, wrong);
		}

		try {
			Files.deleteIfExists(config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("The web map's markers are wrong:\n" + String.join("\n", wrong));
		}
	}

	private static void place(RealServer server, String dimension, int x, int y, int z, String name) {
		server.command("execute in " + dimension + " positioned " + x + " " + y + " " + z + " run gregedcolonies-web marker add " + name);
	}

	private static String marker(String name, String dimension, int x, int y, int z) {
		return "{\"name\":\"" + name + "\",\"dimension\":\"" + dimension + "\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}";
	}

	private static int count(String text, String part) {
		return text.split(Pattern.quote(part), -1).length - 1;
	}

	private static void expect(List<String> wrong, boolean right, String otherwise) {
		if (!right) {
			wrong.add(otherwise);
		}
	}

	/** Waits until a path's text has {@code part} in it, and returns the text. A test server can take half a minute to get to a console command while it saves. */
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
			throw new AssertionError(path + " never had " + part + " in it: " + text[0], e);
		}
		return text[0];
	}

	/** Waits for markers.json to have, or no longer have, {@code part}; says so in {@code wrong} otherwise. */
	private static void waitFor(ClientGameTestContext context, HttpClient http, int port, String part, boolean there, List<String> wrong) {
		int[] asked = {0};
		try {
			context.waitFor(mc -> asked[0]++ % 10 == 0 && status(http, port, "/markers.json") == 200 && get(http, port, "/markers.json").contains(part) == there, 2400);
		} catch (AssertionError e) {
			wrong.add("markers.json " + (there ? "never had " : "still has ") + part + ": " + get(http, port, "/markers.json"));
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

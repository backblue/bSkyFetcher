package org.backblue;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Main {

    static Instant lastPostStamp;
    static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static String did;
    static String user;
    static String pass;
    static String token;
    static String channelId;
    static Properties PROPERTIES;

    public static TextChannel getChannel(JDA bot) {
        return bot.getTextChannelById(channelId);
    }

    public static JSONObject generateLogin() {
        JSONObject login = new JSONObject();
        login.put("identifier", user);
        login.put("password", pass);
        return login;
    }

    public static JSONObject doTheThing() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://bsky.social/xrpc/com.atproto.server.createSession"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(generateLogin().toString()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JSONObject responseJson = new JSONObject(response.body());
        String accessJwt = responseJson.getString("accessJwt");
        String endpoint = "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=" + did;
        HttpRequest requestHttp = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer " + accessJwt)
                .GET()
                .build();

        HttpResponse<String> responseHttp = client.send(requestHttp, HttpResponse.BodyHandlers.ofString());
        JSONObject data = new JSONObject(responseHttp.body());

        JSONArray feed = data.getJSONArray("feed");

        return feed.getJSONObject(0).getJSONObject("post");
    }

    public static void refreshForPosts(JDA bot) throws IOException, InterruptedException {
        JSONObject post = doTheThing();

        Instant createdAt = Instant.parse(post.getJSONObject("record").getString("createdAt"));
        if (lastPostStamp.toString().equals(createdAt.toString())) {
            System.out.println("Already posted this one, skipping.");
            return;
        }
        lastPostStamp = createdAt;

        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(Color.CYAN);
        StringBuilder desc = new StringBuilder(post.getJSONObject("record").getString("text"));

        JSONArray facets = null;
        try {
            facets = post.getJSONObject("record").getJSONArray("facets");
        } catch (JSONException ignored) {}
        int shiftedDesc = 0;
        for (int i = 0; facets != null && i < facets.length(); i++) {
            JSONObject facet = facets.getJSONObject(i);
            String type = facet.getJSONArray("features").getJSONObject(0).getString("$type");
            int start = facet.getJSONObject("index").getInt("byteStart") + shiftedDesc;
            int end = facet.getJSONObject("index").getInt("byteEnd") + shiftedDesc;
            if (type.equals("app.bsky.richtext.facet#link")) {
                byte[] descInBytes = desc.toString().getBytes();

                byte[] descByteToLink = Arrays.copyOfRange(descInBytes, 0, start);
                int sizeofDescByteToLink = descByteToLink.length + 1;
                byte[] sizeofDescByteToLinkWithBracket = new byte[sizeofDescByteToLink];
                System.arraycopy(descByteToLink, 0, sizeofDescByteToLinkWithBracket, 0, sizeofDescByteToLink - 1);
                sizeofDescByteToLinkWithBracket[sizeofDescByteToLink - 1] = (byte) '[';
                byte[] sizeofDescByteToLinkWithLeftBracketAndContent = new byte[sizeofDescByteToLink + (end - start) + 2];
                System.arraycopy(sizeofDescByteToLinkWithBracket, 0, sizeofDescByteToLinkWithLeftBracketAndContent, 0, sizeofDescByteToLink);
                byte[] linkInBytes = Arrays.copyOfRange(descInBytes, start, end);
                System.arraycopy(linkInBytes, 0, sizeofDescByteToLinkWithLeftBracketAndContent, sizeofDescByteToLink, end - start);
                sizeofDescByteToLinkWithLeftBracketAndContent[sizeofDescByteToLinkWithLeftBracketAndContent.length - 2] = ']';
                sizeofDescByteToLinkWithLeftBracketAndContent[sizeofDescByteToLinkWithLeftBracketAndContent.length - 1] = '(';
                byte[] linkUriInBytes = facet.getJSONArray("features").getJSONObject(0).getString("uri").getBytes();
                byte[] descWithURI = new byte[sizeofDescByteToLinkWithLeftBracketAndContent.length + linkUriInBytes.length + 1];
                System.arraycopy(sizeofDescByteToLinkWithLeftBracketAndContent, 0, descWithURI, 0, sizeofDescByteToLinkWithLeftBracketAndContent.length);
                System.arraycopy(linkUriInBytes, 0, descWithURI, sizeofDescByteToLinkWithLeftBracketAndContent.length, linkUriInBytes.length);
                descWithURI[descWithURI.length - 1] = ')';
                byte[] restOfDescInBytes = Arrays.copyOfRange(descInBytes, end, descInBytes.length);
                desc = new StringBuilder(new String(descWithURI, StandardCharsets.UTF_8));
                desc.append(new String(restOfDescInBytes, StandardCharsets.UTF_8));

                //desc.insert(start, "[");
                //desc.insert(end+1, "](" + facet.getJSONArray("features").getJSONObject(0).getString("uri") + ")");
                shiftedDesc += 4 + facet.getJSONArray("features").getJSONObject(0).getString("uri").getBytes().length;
            }
        }

        embed.setDescription(desc);
        embed.setFooter("BlueSky", "https://media.discordapp.net/attachments/1281683524688281704/1393415080200110153/Bluesky_Logo.svg.png?ex=6873166b&is=6871c4eb&hm=6e09c86a44316b789719fda166ba1617ace6ac7ea32228102540ae0f0b4c3ff5&=&format=webp&quality=lossless");

        embed.setTimestamp(createdAt);
        embed.setAuthor(post.getJSONObject("author").getString("displayName"),
                "https://bsky.app/profile/" + post.getJSONObject("author").getString("handle"),
                post.getJSONObject("author").getString("avatar"));
        try {
            embed.setImage(post.getJSONObject("embed").getJSONObject("external").getString("thumb"));
        } catch (JSONException ignored) {}

        String[] parts = post.getString("uri").split("/");
        String rKey = parts[parts.length - 1];
        String urlInTxt = "https://bsky.app/profile/" + post.getJSONObject("author").getString("handle") + "/post/" + rKey;

        getChannel(bot).sendMessage(urlInTxt).setEmbeds(embed.build()).queue();
        if (PROPERTIES.get("DEBUG").equals("true")) {
            System.out.println("New post found and posted!");
        }
        JSONObject cache = new JSONObject();
        cache.put("lastTimestamp", lastPostStamp.toString());
        Files.writeString(Path.of("bSkyFetch/cache.json"), cache.toString());
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        try {
            JSONObject cache = new JSONObject(Files.readString(Path.of("bSkyFetch/cache.json")));
            lastPostStamp = Instant.parse(cache.getString("lastTimestamp"));
        } catch (IOException e) {
            throw new IOException("Need cache file: Make a file in './bSkyFetch/cache.json'");
        } catch (DateTimeParseException e) {
            lastPostStamp = Instant.EPOCH;
        }

        PROPERTIES = new Properties();
        PROPERTIES.load(Files.newBufferedReader(Path.of("bSkyFetch/fetch.properties")));
        did = PROPERTIES.getProperty("DID");
        user = PROPERTIES.getProperty("USER");
        pass = PROPERTIES.getProperty("PASSWORD");
        token = PROPERTIES.getProperty("DISCORD_TOKEN");
        channelId = PROPERTIES.getProperty("DISCORD_CHANNEL");

        JDA bot;
        if (PROPERTIES.get("DISCORD_LINK_ENABLED").equals("true")) {
            bot = JDABuilder.createDefault(token).build();
        } else {
            bot = null;
        }
        long timespan = Long.parseLong((String) PROPERTIES.get("COOLDOWN"));
        refreshForPosts(bot);
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (PROPERTIES.get("DEBUG").equals("true")) {
                    System.out.println("Checking for new posts...");
                }
                if (bot != null) {
                    refreshForPosts(bot);
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }, timespan, timespan, java.util.concurrent.TimeUnit.SECONDS);
    }
}
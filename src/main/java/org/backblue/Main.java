package org.backblue;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

        JSONArray facets = post.getJSONObject("record").getJSONArray("facets");
        int shiftedDesc = 0;
        for (int i = 0; i < facets.length(); i++) {
            JSONObject facet = facets.getJSONObject(i);
            String type = facet.getJSONArray("features").getJSONObject(0).getString("$type");
            int start = facet.getJSONObject("index").getInt("byteStart") + shiftedDesc;
            int end = facet.getJSONObject("index").getInt("byteEnd") + shiftedDesc;
            if (type.equals("app.bsky.richtext.facet#link")) {
                desc.insert(end, "](" + facet.getJSONArray("features").getJSONObject(0).getString("uri") + ")");
                desc.insert(start, "[");
                shiftedDesc += 4 + facet.getJSONArray("features").getJSONObject(0).getString("uri").length();
            }
            if (type.equals("app.bsky.richtext.facet#tag")) {
                String hashtagLink = "https://bsky.app/hashtag/" + facet.getJSONArray("features").getJSONObject(0).getString("tag") + "?author=" + post.getJSONObject("author").getString("handle");
                desc.insert(end, "](" + hashtagLink + ")");
                desc.insert(start, "[");
                shiftedDesc += 4 + hashtagLink.length();
            }

        }

        embed.setDescription(desc);
        embed.setFooter("BlueSky", "https://media.discordapp.net/attachments/1281683524688281704/1393415080200110153/Bluesky_Logo.svg.png?ex=6873166b&is=6871c4eb&hm=6e09c86a44316b789719fda166ba1617ace6ac7ea32228102540ae0f0b4c3ff5&=&format=webp&quality=lossless");

        embed.setTimestamp(createdAt);
        embed.setAuthor(post.getJSONObject("author").getString("displayName"),
                "https://bsky.app/profile/" + post.getJSONObject("author").getString("handle"),
                post.getJSONObject("author").getString("avatar"));
        embed.setImage(post.getJSONObject("embed").getJSONObject("external").getString("thumb"));

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

    public static void main(String[] args) throws IOException {
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
        }, 0L, timespan, java.util.concurrent.TimeUnit.SECONDS);
    }
}
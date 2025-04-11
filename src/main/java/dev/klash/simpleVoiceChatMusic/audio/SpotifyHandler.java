package dev.klash.simpleVoiceChatMusic.audio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SpotifyHandler {

    // Load environment variables with dotenv.
    //private static final Dotenv dotenv = Dotenv.load();
    private static final String CLIENT_ID = "b1fa641f52254fd3a5e0224749e33bad";
    private static final String CLIENT_SECRET = "51a5a7bc3483450dbc0c4f81516f5a94";

    private static final String AUTH_URL = "https://accounts.spotify.com/api/token";
    private static final String TRACK_ENDPOINT = "https://api.spotify.com/v1/tracks/";
    private static final OkHttpClient httpClient = new OkHttpClient();


     //Extracts the Spotify track ID from a given Spotify URL.

    public static String extractTrackId(String spotifyUrl) {
        if (spotifyUrl != null && spotifyUrl.contains("open.spotify.com/track/")) {
            String[] parts = spotifyUrl.split("open.spotify.com/track/");
            if (parts.length > 1) {
                String idAndMore = parts[1];
                // Remove any parameters after '?'
                return idAndMore.split("\\?")[0];
            }
        }
        return null;
    }


     //Obtains an access token using the Spotify Client Credentials flow.
    public static String getAccessToken() throws IOException {
        String credentials = Credentials.basic(CLIENT_ID, CLIENT_SECRET);
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .build();

        Request request = new Request.Builder()
                .url(AUTH_URL)
                .post(body)
                .header("Authorization", credentials)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            String responseBody = response.body().string();
            return extractToken(responseBody);
        }
    }

    //Using gson to parse JSON cause screw natively doing it that sucks
    private static String extractToken(String json) {
        // Look for "access_token":"<token>"
        int tokenIndex = json.indexOf("access_token");
        if (tokenIndex == -1) return null;
        int colonIndex = json.indexOf(":", tokenIndex) + 1;
        int firstQuote = json.indexOf("\"", colonIndex);
        int secondQuote = json.indexOf("\"", firstQuote + 1);
        return json.substring(firstQuote + 1, secondQuote);
    }


    //gets the track information from webAPI
    public static TrackInfo fetchTrackInfo(String trackId) throws IOException {
        String accessToken = getAccessToken();
        Request request = new Request.Builder()
                .url(TRACK_ENDPOINT + trackId)
                .header("Authorization", "Bearer " + accessToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            String jsonResponse = response.body().string();
            String title = parseTitle(jsonResponse);
            String[] artists = parseArtists(jsonResponse);
            return new TrackInfo(title, artists);
        }
    }


    private static String parseTitle(String json) {
        Gson gson = new Gson();
        JsonObject root = gson.fromJson(json, JsonObject.class);
        // The track's name is at the root level.
        if (root.has("name"))
            return root.get("name").getAsString();
        return "Unknown Title";
    }

    private static String[] parseArtists(String json) {
        Gson gson = new Gson();
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root.has("artists")) {
            List<String> names = new ArrayList<>();
            for (var artistElement : root.getAsJsonArray("artists")) {
                JsonObject artistObj = artistElement.getAsJsonObject();
                if (artistObj.has("name"))
                    names.add(artistObj.get("name").getAsString());
            }
            return names.toArray(new String[0]);
        }
        return new String[]{"Unknown Artist"};
    }


    //variables that hold track info to send it to youtube-source
    public static class TrackInfo {
        private final String title;
        private final String[] artists;

        public TrackInfo(String title, String[] artists) {
            this.title = title;
            this.artists = artists;
        }

        public String getTitle() {
            return title;
        }

        public String[] getArtists() {
            return artists;
        }

        //returns a combined search query (title and artist names) suitable for a youtube search.
        public String getCombinedQuery() {
            return title + " " + String.join(" ", artists);
        }

        @Override
        public String toString() {
            return "TrackInfo{title='" + title + "', artists=" + String.join(", ", artists) + "}";
        }
    }
}

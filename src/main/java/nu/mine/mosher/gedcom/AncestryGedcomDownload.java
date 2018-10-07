package nu.mine.mosher.gedcom;

// Created by Christopher Alan Mosher on 2018-10-04

import com.google.gson.Gson;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.RequestBuilder;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;

import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AncestryGedcomDownload {
    private final String treeName;
    private final String out;
    private final CloseableHttpClient http = HttpClients.createDefault();
    private final HttpClientContext context = HttpClientContext.create();
    {
        this.context.setCookieStore(new BasicCookieStore());
    }

    private AncestryGedcomDownload(final String treeName, final String out) {
        this.treeName = treeName;
        this.out = out;
    }

    public static void main(final String... args) throws IOException, InterruptedException {
        if (args.length < 1 || 2 < args.length) {
            System.err.println("usage: java -jar ancestry-gedcom-download treename [output.ged]");
            System.err.println("requires ./.ancestry.properties file with username and password.");
            return;
        }
        new AncestryGedcomDownload(args[0], 1 < args.length? args[1] : "").run();
    }

    private void run() throws IOException, InterruptedException {
        logIn(getCredentials());
        System.err.println("Successfully logged in to Ancestry.com.");

        final TreesInfo.TreeInfo treeInfo = getTreeList();
        System.err.println("Found Ancestry.com tree " + treeInfo.name + ", with ID " + treeInfo.id + " (for account with ID " + treeInfo.ownerUserId + ")");

        final String templateUrl = getDownloadUrl(treeInfo);
        System.err.println("Will use this pattern for the download URL: " + templateUrl);

        final String gid = startGedcomBuild(treeInfo);
        System.err.println("Ancestry.com is creating the GEDCOM file (process ID: " + gid + ")...");

        final String surlDownload = templateUrl.replace("{0}", gid);

        waitForGedcomBuild(treeInfo, gid);
        System.err.println("Will download using this URL: " + surlDownload);

        downloadGedcom(surlDownload);

        System.err.flush();
        System.out.flush();
    }

    private void downloadGedcom(final String surlDownload) throws IOException {
        final HttpGet httpGet = new HttpGet(surlDownload);
        try (final CloseableHttpResponse response = http.execute(httpGet, context)) {
            final HttpEntity entity = parseResponse(response);
            entity.writeTo(getOutputStream());
        }
    }

    private FileOutputStream getOutputStream() throws FileNotFoundException {
        if (this.out.isEmpty()) {
            return new FileOutputStream(FileDescriptor.out);
        }
        return new FileOutputStream(new File(this.out));
    }

    private void waitForGedcomBuild(final TreesInfo.TreeInfo treeInfo, final String gid) throws InterruptedException, IOException {
        boolean complete = false;
        for (int i = 0; i < 120 && !complete; ++i) {
            Thread.sleep(409L);
            complete = checkGedcomBuildProgress(treeInfo, gid, complete);
        }
        if (!complete) {
            throw new IOException("Ancestry.com is taking too long to create the GEDCOM file. Investigation required. Aborting download now.");
        }
    }

    private boolean checkGedcomBuildProgress(final TreesInfo.TreeInfo treeInfo, final String gid, boolean complete) throws IOException {
        final int progress;
        final ClassicHttpRequest httpGet = RequestBuilder
                .get()
                .setUri(URL_STATUS)
                .addParameter("gid", gid)
                .addParameter("uid", treeInfo.ownerUserId)
                .build();
        try (final CloseableHttpResponse response = http.execute(httpGet, context)) {
            final HttpEntity entity = parseResponse(response);
            progress = getProgress(entity);
            if (100 <= progress) {
                complete = true;
            }
            EntityUtils.consume(entity);
        }
        System.err.println("Ancestry.com reported GEDCOM file creation progress of: " + progress + "%...");
        return complete;
    }

    private String startGedcomBuild(final TreesInfo.TreeInfo treeInfo) throws IOException {
        String gid;
        final ClassicHttpRequest httpGet = RequestBuilder
                .get()
                .setUri(URL_EXPORT)
                .addParameter("tid", treeInfo.id)
                .addParameter("uid", treeInfo.ownerUserId)
                .build();
        try (final CloseableHttpResponse response = http.execute(httpGet, context)) {
            final HttpEntity entity = parseResponse(response);
            gid = getGid(entity);
            EntityUtils.consume(entity);
        }
        return gid;
    }

    private static HttpEntity parseResponse(final ClassicHttpResponse response) throws IOException {
        final int st = response.getCode() / 100;
        if (3 <= st) {
            throw new IOException("response status code from server: " + response.getCode());
        }
        return response.getEntity();
    }

    private String getDownloadUrl(final TreesInfo.TreeInfo treeInfo) throws IOException {
        final String templateUrl;
        final HttpGet httpGet = new HttpGet(String.format(FORMAT_URL_SETTINGS_PAGE, treeInfo.id));
        try (final CloseableHttpResponse response = http.execute(httpGet, context)) {
            final HttpEntity entity = parseResponse(response);
            try (final BufferedReader readerPage = new BufferedReader(new InputStreamReader(entity.getContent()))) {
                templateUrl = readerPage
                        .lines()
                        .map(SCRAPE_FOR::matcher)
                        .filter(Matcher::matches)
                        .limit(1)
                        .map(mat -> mat.group(1))
                        .findFirst()
                        .orElseThrow();
            }

            EntityUtils.consume(entity);
        }
        return templateUrl;
    }

    private TreesInfo.TreeInfo getTreeList() throws IOException {
        TreesInfo.TreeInfo treeInfo;
        final HttpGet httpGet = new HttpGet(URL_TREES);
        try (final CloseableHttpResponse response = http.execute(httpGet, context)) {
            final HttpEntity entity = parseResponse(response);
            final Map<String, TreesInfo.TreeInfo> mapLowerNameToTree = getTrees(entity);
            System.err.println("Found " + mapLowerNameToTree.size() + " trees for Ancestry.com account.");
            treeInfo = mapLowerNameToTree.get(treeName.toLowerCase());
            if (Objects.isNull(treeInfo)) {
                throw new IOException("Cannot find Ancestry.com tree: " + treeName);
            }
            EntityUtils.consume(entity);
        }
        return treeInfo;
    }

    private void logIn(final Credentials credendials) throws IOException {
        final HttpPost httpPost = new HttpPost(URL_SIGNIN);
        final List<NameValuePair> nvps = List.of(
                new BasicNameValuePair("username", credendials.username),
                new BasicNameValuePair("password", credendials.password));
        httpPost.setEntity(new UrlEncodedFormEntity(nvps));

        try (final CloseableHttpResponse response = http.execute(httpPost, context)) {
            final HttpEntity entity = parseResponse(response);
            EntityUtils.consume(entity);
        }
    }

    private static Credentials getCredentials() {
        final Credentials credentials = new Credentials();
        try {
            final Configurations configs = new Configurations();
            final PropertiesConfiguration config = configs.properties(".ancestry.properties");
            credentials.username = config.getString("username", "");
            credentials.password = config.getString("password", "");
        } catch (final ConfigurationException e) {
            System.err.println("Cannot find configuration file ~/.ancestry.properties; will prompt for Ancestry.com credentials:");
        }
        if (credentials.username.isEmpty()) {
            final Console console = System.console();
            if (Objects.isNull(console)) {
                throw new UnsupportedOperationException("Cannot read from the console.");
            }
            credentials.username = console.readLine("%s: ", "username");
        }
        if (credentials.password.isEmpty()) {
            final Console console = System.console();
            if (Objects.isNull(console)) {
                throw new UnsupportedOperationException("Cannot read from the console.");
            }
            credentials.password = new String(console.readPassword("%s: ", "password"));
        }
        return credentials;
    }

    private static int getProgress(final HttpEntity entity) throws IOException {
        final Gson gson = new Gson();
        try (final Reader reader = new BufferedReader(new InputStreamReader(entity.getContent()))) {
            final ExportStatus exportStatus = gson.fromJson(reader, ExportStatus.class);
            return Integer.parseInt(exportStatus.progress);
        }
    }

    private static Map<String, TreesInfo.TreeInfo> getTrees(final HttpEntity entity) throws IOException {
        final Gson gson = new Gson();
        try (final Reader reader = new BufferedReader(new InputStreamReader(entity.getContent()))) {
            final TreesInfo treesInfo = gson.fromJson(reader, TreesInfo.class);
            return treesInfo
                    .trees
                    .stream()
                    .collect(Collectors.toMap(k -> k.name.toLowerCase(), Function.identity()));
        }
    }

    private static String getGid(final HttpEntity entity) throws IOException {
        final Gson gson = new Gson();
        try (final Reader reader = new BufferedReader(new InputStreamReader(entity.getContent()))) {
            final ExportInfo exportInfo = gson.fromJson(reader, ExportInfo.class);
            return exportInfo.gid;
        }
    }

    private static final String URL_SIGNIN = "https://www.ancestry.com/account/signin";
    private static final String URL_TREES = "https://www.ancestry.com/api/treesui-list/trees?rights=own";
    private static final String URL_EXPORT = "https://www.ancestry.com/family-tree/ExportGedcom.ashx"; // tid, uid
    private static final String URL_STATUS = "https://www.ancestry.com/family-tree/getexportgedcomstatus.ashx"; // gid, uid
    private static final String FORMAT_URL_SETTINGS_PAGE = "https://www.ancestry.com/family-tree/tree/%s/settings/info";
    private static final Pattern SCRAPE_FOR = Pattern.compile(".*'(http.*\\{0}\\.ged.*\\.ged)'.*");

    private static class TreesInfo {
        private static class TreeInfo {
            String id;
            String name;
            String ownerUserId;
            String dateModified;
            String dateCreated;
            int totalInvitedCount;
        }

        Set<TreeInfo> trees;
        int count;
    }

    private static class ExportInfo {
        String initiateStatus;
        String gid;
    }

    private static class ExportStatus {
        String uid;
        String gid;
        String progress;
    }

    private static class Credentials {
        String username = "";
        String password = "";
    }
}

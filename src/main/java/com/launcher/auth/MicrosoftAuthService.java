package com.launcher.auth;

import com.google.gson.JsonObject;
import com.launcher.model.Account;
import com.launcher.model.AccountType;
import com.launcher.util.HttpUtil;
import com.launcher.util.JsonUtil;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Implements the Microsoft -> Xbox Live -> XSTS -> Minecraft Services auth chain
 * used by the official launcher (device code flow, so no embedded browser is needed).
 *
 * IMPORTANT SETUP STEP (read the README):
 * You must register your own free Azure AD application at https://portal.azure.com
 * (Azure Active Directory > App registrations > New registration), set it as a
 * "Public client / native" app with "Allow public client flows" = Yes, and paste
 * the Application (client) ID below. Microsoft does not allow distributing a
 * shared client ID baked into third-party launchers.
 */
public class MicrosoftAuthService {

    // TODO: replace with your own Azure AD application (client) ID - see README.
    public static String CLIENT_ID = "00000000-0000-0000-0000-000000000000";

    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    public DeviceCodeInfo requestDeviceCode() throws IOException, InterruptedException {
        String body = "client_id=" + enc(CLIENT_ID) + "&scope=" + enc(SCOPE);
        String resp = HttpUtil.postFormRaw(DEVICE_CODE_URL, body);
        JsonObject obj = JsonUtil.parse(resp).getAsJsonObject();
        if (obj.has("error")) {
            throw new IOException("Device code request failed: " + resp);
        }
        DeviceCodeInfo info = new DeviceCodeInfo();
        info.deviceCode = obj.get("device_code").getAsString();
        info.userCode = obj.get("user_code").getAsString();
        info.verificationUri = obj.has("verification_uri") ? obj.get("verification_uri").getAsString()
                : obj.get("verification_uri_complete").getAsString();
        info.expiresIn = obj.get("expires_in").getAsInt();
        info.interval = obj.has("interval") ? obj.get("interval").getAsInt() : 5;
        return info;
    }

    /** Result of step 1: a raw Microsoft OAuth token pair, before the Xbox/Minecraft exchange. */
    public static class MsTokens {
        public String accessToken;
        public String refreshToken;
    }

    /** Polls the token endpoint until the user finishes signing in in their browser, or it expires/errors. */
    public MsTokens pollForToken(DeviceCodeInfo info, java.util.function.BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + info.expiresIn * 1000L;
        String body = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                + "&client_id=" + enc(CLIENT_ID)
                + "&device_code=" + enc(info.deviceCode);

        while (System.currentTimeMillis() < deadline) {
            if (cancelled.getAsBoolean()) throw new IOException("Cancelled by user");
            Thread.sleep(info.interval * 1000L);
            String resp = HttpUtil.postFormRaw(TOKEN_URL, body);
            JsonObject obj = JsonUtil.parse(resp).getAsJsonObject();
            if (obj.has("access_token")) {
                MsTokens t = new MsTokens();
                t.accessToken = obj.get("access_token").getAsString();
                t.refreshToken = obj.has("refresh_token") ? obj.get("refresh_token").getAsString() : null;
                return t;
            }
            String error = obj.has("error") ? obj.get("error").getAsString() : "unknown_error";
            if (error.equals("authorization_pending")) {
                continue; // user hasn't finished signing in yet, keep polling
            } else if (error.equals("slow_down")) {
                info.interval += 5;
                continue;
            } else {
                throw new IOException("Sign-in failed: " + error);
            }
        }
        throw new IOException("Device code expired - please try signing in again");
    }

    public MsTokens refreshToken(String refreshToken) throws IOException, InterruptedException {
        String body = "grant_type=refresh_token"
                + "&client_id=" + enc(CLIENT_ID)
                + "&refresh_token=" + enc(refreshToken)
                + "&scope=" + enc(SCOPE);
        String resp = HttpUtil.postFormRaw(TOKEN_URL, body);
        JsonObject obj = JsonUtil.parse(resp).getAsJsonObject();
        if (!obj.has("access_token")) {
            throw new IOException("Token refresh failed: " + resp);
        }
        MsTokens t = new MsTokens();
        t.accessToken = obj.get("access_token").getAsString();
        t.refreshToken = obj.has("refresh_token") ? obj.get("refresh_token").getAsString() : refreshToken;
        return t;
    }

    /** Full chain: MS access token -> Xbox Live token -> XSTS token -> Minecraft access token -> profile. */
    public Account completeLogin(MsTokens msTokens) throws IOException, InterruptedException {
        // Step 1: Xbox Live "user.auth" token
        String xblBody = "{"
                + "\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\","
                + "\"RpsTicket\":\"d=" + msTokens.accessToken + "\"},"
                + "\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}";
        String xblResp = HttpUtil.postJson("https://user.auth.xboxlive.com/user/authenticate", xblBody);
        JsonObject xblObj = JsonUtil.parse(xblResp).getAsJsonObject();
        String xblToken = xblObj.get("Token").getAsString();
        String uhs = xblObj.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                .get(0).getAsJsonObject().get("uhs").getAsString();

        // Step 2: XSTS token, authorized for the Minecraft API relying party
        String xstsBody = "{"
                + "\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + xblToken + "\"]},"
                + "\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}";
        String xstsResp = HttpUtil.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody);
        JsonObject xstsObj = JsonUtil.parse(xstsResp).getAsJsonObject();
        if (xstsObj.has("XErr")) {
            long xerr = xstsObj.get("XErr").getAsLong();
            throw new IOException(describeXstsError(xerr));
        }
        String xstsToken = xstsObj.get("Token").getAsString();

        // Step 3: Minecraft Services access token
        String mcBody = "{\"identityToken\":\"XBL3.0 x=" + uhs + ";" + xstsToken + "\"}";
        String mcResp = HttpUtil.postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcBody);
        JsonObject mcObj = JsonUtil.parse(mcResp).getAsJsonObject();
        String mcAccessToken = mcObj.get("access_token").getAsString();
        int expiresIn = mcObj.has("expires_in") ? mcObj.get("expires_in").getAsInt() : 86400;

        // Step 4 (recommended): verify game ownership
        try {
            String entResp = HttpUtil.getStringAuthorized("https://api.minecraftservices.com/entitlements/mcstore", mcAccessToken);
            JsonObject entObj = JsonUtil.parse(entResp).getAsJsonObject();
            if (entObj.getAsJsonArray("items").isEmpty()) {
                System.err.println("Warning: this Microsoft account shows no Minecraft entitlement - it may not own the game.");
            }
        } catch (Exception ignored) {
            // Non-fatal: some accounts/regions respond oddly here; don't block login over it.
        }

        // Step 5: profile (username + uuid)
        String profResp = HttpUtil.getStringAuthorized("https://api.minecraftservices.com/minecraft/profile", mcAccessToken);
        JsonObject profObj = JsonUtil.parse(profResp).getAsJsonObject();
        if (profObj.has("error") || !profObj.has("name")) {
            throw new IOException("Could not load Minecraft profile (does this account own the game?): " + profResp);
        }

        Account account = new Account();
        account.id = java.util.UUID.randomUUID().toString();
        account.type = AccountType.MICROSOFT;
        account.username = profObj.get("name").getAsString();
        account.uuid = formatUuid(profObj.get("id").getAsString());
        account.msAccessToken = msTokens.accessToken;
        account.msRefreshToken = msTokens.refreshToken;
        account.mcAccessToken = mcAccessToken;
        account.mcAccessTokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        return account;
    }

    /** Re-runs the Xbox/XSTS/Minecraft exchange using a fresh MS access token (after refreshToken()). */
    public void refreshAccount(Account account) throws IOException, InterruptedException {
        MsTokens refreshed = refreshToken(account.msRefreshToken);
        Account updated = completeLogin(refreshed);
        account.msAccessToken = updated.msAccessToken;
        account.msRefreshToken = updated.msRefreshToken != null ? updated.msRefreshToken : account.msRefreshToken;
        account.mcAccessToken = updated.mcAccessToken;
        account.mcAccessTokenExpiresAt = updated.mcAccessTokenExpiresAt;
        account.username = updated.username;
        account.uuid = updated.uuid;
    }

    private static String describeXstsError(long code) {
        if (code == 2148916233L) return "This Microsoft account has no Xbox profile. Sign in to xbox.com once to create one, then retry.";
        if (code == 2148916235L) return "Xbox Live is not available in this account's country/region.";
        if (code == 2148916236L || code == 2148916237L) return "This account needs adult verification (South Korea age requirements).";
        if (code == 2148916238L) return "This is a child account - an adult needs to add it to a Family group first.";
        return "Xbox Live authorization failed (XErr " + code + ")";
    }

    private static String formatUuid(String undashed) {
        if (undashed.contains("-")) return undashed;
        return undashed.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

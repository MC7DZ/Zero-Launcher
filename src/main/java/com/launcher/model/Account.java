package com.launcher.model;

import java.util.UUID;

public class Account {
    public String id;
    public String username;
    public String uuid;
    public AccountType type;

    public String msAccessToken;
    public String msRefreshToken;
    public String mcAccessToken;
    public long mcAccessTokenExpiresAt;

    public Account() {}

    public static Account offline(String username) {
        Account a = new Account();
        a.id = UUID.randomUUID().toString();
        a.username = username;
        a.type = AccountType.OFFLINE;
        a.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()).toString();
        return a;
    }

    public boolean isMicrosoftTokenExpired() {
        return System.currentTimeMillis() >= mcAccessTokenExpiresAt - 30_000;
    }
}

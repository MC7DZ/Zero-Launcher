package com.launcher.model;

import java.util.UUID;

public class Account {
    public String id;
    public String username;
    public String uuid; // Keep UUID for offline accounts
    public AccountType type;

    public Account() {}

    public static Account offline(String username) {
        Account a = new Account();
        a.id = UUID.randomUUID().toString();
        a.username = username;
        a.type = AccountType.OFFLINE;
        // Generate a consistent UUID for offline players based on username
        a.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()).toString();
        return a;
    }
}
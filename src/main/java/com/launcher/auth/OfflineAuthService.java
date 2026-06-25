package com.launcher.auth;

import com.launcher.model.Account;

public class OfflineAuthService {
    /** Offline accounts need no network call - just a username, mirroring vanilla "offline mode". */
    public Account login(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        return Account.offline(username.trim());
    }
}

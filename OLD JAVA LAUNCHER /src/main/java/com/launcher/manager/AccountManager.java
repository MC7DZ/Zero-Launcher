package com.launcher.manager;

import com.launcher.model.Account;
import com.launcher.util.JsonUtil;

import java.lang.reflect.Type;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AccountManager {
    private List<Account> accounts;
    private String activeAccountId;

    private static class Store {
        List<Account> accounts = new ArrayList<>();
        String activeAccountId;
    }

    private Store store;

    public AccountManager() {
        load();
    }

    private void load() {
        Type type = new TypeToken<Store>(){}.getType();
        store = com.launcher.util.JsonUtil.GSON.fromJson(
                readOrEmpty(), type);
        if (store == null) store = new Store();
        if (store.accounts == null) store.accounts = new ArrayList<>();
        this.accounts = store.accounts;
        this.activeAccountId = store.activeAccountId;
    }

    private String readOrEmpty() {
        try {
            var path = LauncherPaths.accountsFile();
            if (!java.nio.file.Files.exists(path)) return "{}";
            return java.nio.file.Files.readString(path);
        } catch (Exception e) {
            return "{}";
        }
    }

    public void save() {
        store.accounts = accounts;
        store.activeAccountId = activeAccountId;
        JsonUtil.writeFile(LauncherPaths.accountsFile(), store);
    }

    public List<Account> getAccounts() {
        return accounts;
    }

    public void addOrUpdate(Account account) {
        accounts.removeIf(a -> a.id.equals(account.id));
        accounts.add(account);
        if (activeAccountId == null) activeAccountId = account.id;
        save();
    }

    public void remove(Account account) {
        accounts.removeIf(a -> a.id.equals(account.id));
        if (account.id.equals(activeAccountId)) {
            activeAccountId = accounts.isEmpty() ? null : accounts.get(0).id;
        }
        save();
    }

    public Optional<Account> getActiveAccount() {
        if (activeAccountId == null) return Optional.empty();
        return accounts.stream().filter(a -> a.id.equals(activeAccountId)).findFirst();
    }

    public void setActiveAccount(Account account) {
        this.activeAccountId = account.id;
        save();
    }
}

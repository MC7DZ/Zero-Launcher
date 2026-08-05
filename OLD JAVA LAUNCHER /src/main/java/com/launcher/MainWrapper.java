package com.launcher;

public class MainWrapper {
    public static void main(String[] args) {
        // هذا السطر يقوم بخداع نظام الحماية في JavaFX لتشغيله من داخل الـ JAR المدمج
        Main.main(args);
    }
}